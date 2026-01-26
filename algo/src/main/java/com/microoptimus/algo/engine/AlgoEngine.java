package com.microoptimus.algo.engine;

import com.microoptimus.algo.algorithms.*;
import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoOrder.AlgorithmType;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;
import com.microoptimus.algo.state.AlgoOrderState;
import com.microoptimus.common.types.Side;
import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * AlgoEngine - Main orchestrator for algorithmic order execution
 *
 * Responsibilities:
 * - Manages lifecycle of algo orders
 * - Instantiates and runs algorithms
 * - Generates slices and sends to SOR
 * - Handles execution feedback
 */
public class AlgoEngine {

    private static final Logger log = LoggerFactory.getLogger(AlgoEngine.class);

    // Active algo orders
    private final Long2ObjectHashMap<AlgoOrder> activeOrders;
    private final Long2ObjectHashMap<Algorithm> orderAlgorithms;
    private final Long2ObjectHashMap<AlgoOrder> sliceToOrder; // Map slice ID to parent order

    // Order pool
    private final AlgoOrder[] orderPool;
    private int orderPoolIndex;
    private static final int ORDER_POOL_SIZE = 1024;

    // Algorithm instances (reusable)
    private final TWAPAlgorithm twapAlgorithm;
    private final VWAPAlgorithm vwapAlgorithm;
    private final IcebergAlgorithm icebergAlgorithm;

    // Callbacks
    private Consumer<Slice> sliceCallback;
    private Consumer<AlgoOrder> orderUpdateCallback;

    // ID generators
    private long nextOrderId = 1;

    // Statistics
    private long ordersReceived;
    private long ordersCompleted;
    private long slicesGenerated;
    private long slicesFilled;

    public AlgoEngine() {
        this.activeOrders = new Long2ObjectHashMap<>();
        this.orderAlgorithms = new Long2ObjectHashMap<>();
        this.sliceToOrder = new Long2ObjectHashMap<>();

        // Initialize order pool
        this.orderPool = new AlgoOrder[ORDER_POOL_SIZE];
        for (int i = 0; i < ORDER_POOL_SIZE; i++) {
            orderPool[i] = new AlgoOrder();
        }
        this.orderPoolIndex = 0;

        // Initialize algorithm instances
        this.twapAlgorithm = new TWAPAlgorithm();
        this.vwapAlgorithm = new VWAPAlgorithm();
        this.icebergAlgorithm = new IcebergAlgorithm();
    }

    /**
     * Set callback for generated slices (to SOR)
     */
    public void setSliceCallback(Consumer<Slice> callback) {
        this.sliceCallback = callback;
    }

    /**
     * Set callback for order updates
     */
    public void setOrderUpdateCallback(Consumer<AlgoOrder> callback) {
        this.orderUpdateCallback = callback;
    }

    /**
     * Submit a new algo order
     */
    public AlgoOrder submitOrder(long clientId, int symbolIndex, Side side,
                                  long totalQuantity, long limitPrice,
                                  AlgorithmType algorithmType, AlgoParameters params,
                                  long startTime, long endTime, long timestamp) {

        // Acquire order from pool
        AlgoOrder order = acquireOrder();
        long orderId = nextOrderId++;

        order.init(orderId, clientId, symbolIndex, side, totalQuantity, limitPrice,
                   algorithmType, startTime, endTime, timestamp);
        order.setParameters(params);

        // Get algorithm for this order type
        Algorithm algorithm = getAlgorithm(algorithmType);
        algorithm.initialize(order);

        // Store order and algorithm
        activeOrders.put(orderId, order);
        orderAlgorithms.put(orderId, algorithm);

        ordersReceived++;
        log.info("Algo order submitted: {}", order);

        return order;
    }

    /**
     * Start an algo order
     */
    public boolean startOrder(long orderId, long timestamp) {
        AlgoOrder order = activeOrders.get(orderId);
        if (order == null) {
            return false;
        }

        if (!order.getState().canStart()) {
            return false;
        }

        order.start(timestamp);
        notifyOrderUpdate(order);
        log.info("Algo order started: {}", orderId);
        return true;
    }

    /**
     * Pause an algo order
     */
    public boolean pauseOrder(long orderId, long timestamp) {
        AlgoOrder order = activeOrders.get(orderId);
        if (order == null || !order.getState().canPause()) {
            return false;
        }

        order.pause(timestamp);
        notifyOrderUpdate(order);
        log.info("Algo order paused: {}", orderId);
        return true;
    }

    /**
     * Resume a paused order
     */
    public boolean resumeOrder(long orderId, long timestamp) {
        AlgoOrder order = activeOrders.get(orderId);
        if (order == null || !order.getState().canResume()) {
            return false;
        }

        order.resume(timestamp);
        notifyOrderUpdate(order);
        log.info("Algo order resumed: {}", orderId);
        return true;
    }

    /**
     * Cancel an algo order
     */
    public boolean cancelOrder(long orderId, long timestamp) {
        AlgoOrder order = activeOrders.get(orderId);
        if (order == null || !order.getState().canCancel()) {
            return false;
        }

        order.cancel(timestamp);
        completeOrder(order);
        log.info("Algo order cancelled: {}", orderId);
        return true;
    }

    /**
     * Process all active orders - called periodically
     */
    public void processOrders(long currentTime, long currentPrice) {
        activeOrders.forEach((orderId, order) -> {
            if (order.getState().canGenerateSlices()) {
                processOrder(order, currentTime, currentPrice);
            }

            // Check for expiration
            if (currentTime > order.getEndTime() && !order.isTerminal()) {
                order.expire(currentTime);
                completeOrder(order);
            }
        });
    }

    /**
     * Process a single order
     */
    private void processOrder(AlgoOrder order, long currentTime, long currentPrice) {
        Algorithm algorithm = orderAlgorithms.get(order.getAlgoOrderId());
        if (algorithm == null) {
            return;
        }

        // Generate slices
        List<Slice> slices = algorithm.generateSlices(order, currentTime, currentPrice);

        for (Slice slice : slices) {
            // Track slice to parent
            sliceToOrder.put(slice.getSliceId(), order);
            slicesGenerated++;

            // Send to SOR
            if (sliceCallback != null) {
                slice.markSent(currentTime);
                sliceCallback.accept(slice);
            }
        }

        // Check if order is complete
        if (algorithm.isOrderComplete(order)) {
            completeOrder(order);
        }
    }

    /**
     * Handle slice execution feedback
     */
    public void onSliceExecution(long sliceId, long execQty, long execPrice, long timestamp) {
        AlgoOrder order = sliceToOrder.get(sliceId);
        if (order == null) {
            log.warn("Unknown slice execution: {}", sliceId);
            return;
        }

        Algorithm algorithm = orderAlgorithms.get(order.getAlgoOrderId());
        // Find slice (simplified - would use slice tracking)
        // For now, directly update order
        order.onSliceFill(execQty, execPrice, timestamp);
        slicesFilled++;

        notifyOrderUpdate(order);

        // Check if order is complete
        if (algorithm != null && algorithm.isOrderComplete(order)) {
            completeOrder(order);
        }
    }

    /**
     * Handle slice completion
     */
    public void onSliceComplete(long sliceId, boolean filled, long timestamp) {
        AlgoOrder order = sliceToOrder.get(sliceId);
        if (order == null) {
            return;
        }

        if (filled) {
            order.onSliceFilled();
        } else {
            order.onSliceCancelled();
        }

        sliceToOrder.remove(sliceId);
    }

    /**
     * Complete an order (terminal state)
     */
    private void completeOrder(AlgoOrder order) {
        activeOrders.remove(order.getAlgoOrderId());
        orderAlgorithms.remove(order.getAlgoOrderId());
        ordersCompleted++;

        notifyOrderUpdate(order);
        log.info("Algo order completed: {}", order);

        // Return to pool
        releaseOrder(order);
    }

    /**
     * Get algorithm instance for type
     */
    private Algorithm getAlgorithm(AlgorithmType type) {
        return switch (type) {
            case TWAP -> twapAlgorithm;
            case VWAP -> vwapAlgorithm;
            case ICEBERG -> icebergAlgorithm;
            default -> twapAlgorithm; // Default to TWAP
        };
    }

    /**
     * Notify order update callback
     */
    private void notifyOrderUpdate(AlgoOrder order) {
        if (orderUpdateCallback != null) {
            orderUpdateCallback.accept(order);
        }
    }

    // Pool management
    private AlgoOrder acquireOrder() {
        AlgoOrder order = orderPool[orderPoolIndex];
        orderPoolIndex = (orderPoolIndex + 1) % ORDER_POOL_SIZE;
        return order;
    }

    private void releaseOrder(AlgoOrder order) {
        order.reset();
    }

    // Accessors
    public AlgoOrder getOrder(long orderId) {
        return activeOrders.get(orderId);
    }

    public int getActiveOrderCount() {
        return activeOrders.size();
    }

    // Statistics
    public long getOrdersReceived() { return ordersReceived; }
    public long getOrdersCompleted() { return ordersCompleted; }
    public long getSlicesGenerated() { return slicesGenerated; }
    public long getSlicesFilled() { return slicesFilled; }
}
