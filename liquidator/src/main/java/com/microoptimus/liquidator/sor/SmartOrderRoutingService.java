package com.microoptimus.liquidator.sor;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.liquidator.CMEOrderGateway;
import com.microoptimus.liquidator.OrderIDMapper;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SmartOrderRoutingService - Integration service that connects SOR with Aeron Cluster
 *
 * Architecture:
 * 1. Receives orders from OSM via Aeron IPC (when OSM cannot match internally)
 * 2. Routes orders using C++ Smart Order Router
 * 3. Sends execution reports back to OSM via Aeron IPC
 * 4. Maintains integration with global sequencer for consistency
 *
 * Message Flow:
 * Signal/MM → OSM (internal match attempt) → SOR Service → External Venues
 *                    ↓                            ↓
 *            Internal Execution            External Execution
 *                    ↓                            ↓
 *            Back to Signal/MM ←───── OSM ←─── SOR Service
 */
public class SmartOrderRoutingService implements EgressListener {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRoutingService.class);

    // Stream IDs for SOR integration
    private static final int OSM_TO_SOR_STREAM = 3001;     // OSM → SOR orders
    private static final int SOR_TO_OSM_STREAM = 3002;     // SOR → OSM executions
    private static final int SOR_CLUSTER_STREAM = 3003;    // SOR ↔ Cluster coordination

    // Aeron connections
    private final Aeron aeron;
    private final AeronCluster cluster;
    private final Subscription orderSubscription;
    private final Publication executionPublication;

    // SOR components
    private final SmartOrderRouter smartOrderRouter;
    private final SharedMemoryStore sharedMemoryStore;
    private final CMEOrderGateway cmeGateway;
    private final OrderIDMapper orderIdMapper;

    // State management
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedOrders = new AtomicLong(0);
    private final AtomicLong executionsSent = new AtomicLong(0);

    // Configuration
    private final String configPath;
    private final String sharedMemoryPath;

    /**
     * Create SOR service with Aeron cluster integration
     */
    public SmartOrderRoutingService(String configPath, String sharedMemoryPath) throws Exception {
        this.configPath = configPath;
        this.sharedMemoryPath = sharedMemoryPath;

        log.info("Initializing Smart Order Routing Service...");

        // Initialize Aeron connections
        this.aeron = Aeron.connect();

        // Subscribe to orders from OSM
        this.orderSubscription = aeron.addSubscription("aeron:ipc", OSM_TO_SOR_STREAM);

        // Publish executions back to OSM
        this.executionPublication = aeron.addExclusivePublication("aeron:ipc", SOR_TO_OSM_STREAM);

        // Connect to cluster for global coordination
        this.cluster = AeronCluster.connect(new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints("0=localhost:20000")
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener(this));

        // Initialize shared memory store (same as used by other components)
        this.sharedMemoryStore = new SharedMemoryStore(sharedMemoryPath, 128L * 1024 * 1024);

        // Initialize SOR
        this.smartOrderRouter = new SmartOrderRouter();
        if (!smartOrderRouter.initialize(configPath, sharedMemoryPath)) {
            throw new RuntimeException("Failed to initialize Smart Order Router");
        }

        // Initialize order gateways
        this.cmeGateway = new CMEOrderGateway(null); // TODO: Connect execution ring
        this.orderIdMapper = new OrderIDMapper();

        log.info("Smart Order Routing Service initialized successfully");
    }

    /**
     * Start the SOR service event loop
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Smart Order Routing Service...");

            // Start the main event loop
            Thread eventThread = new Thread(this::runEventLoop, "SOR-EventLoop");
            eventThread.setDaemon(false);
            eventThread.start();

            log.info("Smart Order Routing Service started");
        }
    }

    /**
     * Stop the SOR service
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Smart Order Routing Service...");

            // Cleanup resources
            try {
                smartOrderRouter.shutdown();
                sharedMemoryStore.close();
                cluster.close();
                aeron.close();
            } catch (Exception e) {
                log.error("Error during shutdown: {}", e.getMessage());
            }

            log.info("Smart Order Routing Service stopped");
        }
    }

    /**
     * Main event loop - processes orders and maintains cluster integration
     */
    private void runEventLoop() {
        log.info("SOR event loop started");

        while (running.get()) {
            try {
                // Poll for new orders from OSM
                int ordersProcessed = orderSubscription.poll(this::processOrderFromOSM, 10);

                // Poll cluster for global coordination messages
                int clusterMessages = cluster.pollEgress();

                // Yield CPU if no work
                if (ordersProcessed == 0 && clusterMessages == 0) {
                    Thread.yield();
                }

            } catch (Exception e) {
                log.error("Error in SOR event loop: {}", e.getMessage(), e);
                // Continue running unless explicitly stopped
            }
        }

        log.info("SOR event loop stopped");
    }

    /**
     * Process order received from OSM
     */
    private void processOrderFromOSM(DirectBuffer buffer, int offset, int length, Header header) {
        try {
            long globalSequence = header.position(); // Global sequence from Aeron cluster

            // Decode order from buffer
            SmartOrderRouter.OrderRequest order = decodeOrderRequest(buffer, offset, length);

            if (order == null) {
                log.warn("Failed to decode order from OSM");
                return;
            }

            processedOrders.incrementAndGet();

            log.debug("Processing order from OSM: {} (global seq: {})", order, globalSequence);

            // Route order using C++ SOR
            SmartOrderRouter.RoutingDecision decision = smartOrderRouter.routeOrder(order);

            // Handle routing decision
            handleRoutingDecision(order, decision, globalSequence);

        } catch (Exception e) {
            log.error("Error processing order from OSM: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle the routing decision from SOR
     */
    private void handleRoutingDecision(SmartOrderRouter.OrderRequest order,
                                     SmartOrderRouter.RoutingDecision decision,
                                     long globalSequence) {

        log.debug("Routing decision for order {}: {}", order.orderId, decision);

        switch (decision.action) {
            case ROUTE_INTERNAL:
                // Route back to OSM for internal crossing
                routeBackToOSM(order, decision, globalSequence);
                break;

            case ROUTE_EXTERNAL:
                // Route to external venue
                routeToExternalVenue(order, decision, globalSequence);
                break;

            case SPLIT_ORDER:
                // Split order across multiple venues
                executeSplitRouting(order, decision, globalSequence);
                break;

            case REJECT:
                // Reject the order
                rejectOrder(order, decision.rejectReason, globalSequence);
                break;

            default:
                log.warn("Unknown routing action: {} for order {}", decision.action, order.orderId);
                rejectOrder(order, "Unknown routing action", globalSequence);
        }
    }

    /**
     * Route order back to OSM for internal crossing
     */
    private void routeBackToOSM(SmartOrderRouter.OrderRequest order, SmartOrderRouter.RoutingDecision decision,
                               long globalSequence) {
        log.debug("Routing order {} back to OSM for internal crossing", order.orderId);

        // Create execution report indicating internal routing
        ExecutionReport execution = new ExecutionReport(
            generateExecutionId(),
            order.orderId,
            order.symbol,
            order.side,
            0, // No execution yet - will be filled by OSM
            0, // No execution price yet
            ExecutionType.ROUTED_INTERNAL,
            globalSequence,
            System.nanoTime()
        );

        publishExecutionToOSM(execution);
    }

    /**
     * Route order to external venue
     */
    private void routeToExternalVenue(SmartOrderRouter.OrderRequest order, SmartOrderRouter.RoutingDecision decision,
                                    long globalSequence) {
        log.debug("Routing order {} to external venue: {}", order.orderId, decision.primaryVenue);

        switch (decision.primaryVenue) {
            case CME:
                routeToCME(order, globalSequence);
                break;
            case NASDAQ:
                routeToNasdaq(order, globalSequence);
                break;
            case NYSE:
                routeToNYSE(order, globalSequence);
                break;
            default:
                log.warn("Unsupported venue: {} for order {}", decision.primaryVenue, order.orderId);
                rejectOrder(order, "Unsupported venue", globalSequence);
        }
    }

    /**
     * Execute split order routing
     */
    private void executeSplitRouting(SmartOrderRouter.OrderRequest order, SmartOrderRouter.RoutingDecision decision,
                                   long globalSequence) {
        log.debug("Executing split routing for order {} across {} venues",
                 order.orderId, decision.allocations.length);

        for (SmartOrderRouter.VenueAllocation allocation : decision.allocations) {
            // Create child order for each allocation
            SmartOrderRouter.OrderRequest childOrder = new SmartOrderRouter.OrderRequest(
                generateChildOrderId(order.orderId, allocation.priority),
                order.symbol,
                order.side,
                order.orderType,
                order.price,
                allocation.quantity,
                order.timestamp,
                order.clientId
            );

            // Route child order to specific venue
            SmartOrderRouter.RoutingDecision singleVenueDecision =
                new SmartOrderRouter.RoutingDecision(
                    SmartOrderRouter.RoutingAction.ROUTE_EXTERNAL,
                    allocation.venue,
                    allocation.quantity,
                    decision.estimatedFillTimeNanos
                );

            handleRoutingDecision(childOrder, singleVenueDecision, globalSequence);
        }
    }

    /**
     * Route order to CME
     */
    private void routeToCME(SmartOrderRouter.OrderRequest order, long globalSequence) {
        try {
            // Convert to CME order format and send
            // TODO: Implement CME-specific order conversion
            log.info("Routing order {} to CME: {} {} {}@{}",
                    order.orderId, order.side, order.symbol, order.quantity, order.price);

            // Create execution report for routing
            ExecutionReport execution = new ExecutionReport(
                generateExecutionId(),
                order.orderId,
                order.symbol,
                order.side,
                0, // Quantity will be filled by CME
                0, // Price will be filled by CME
                ExecutionType.ROUTED_EXTERNAL,
                globalSequence,
                System.nanoTime()
            );

            execution.targetVenue = SmartOrderRouter.VenueType.CME;
            publishExecutionToOSM(execution);

        } catch (Exception e) {
            log.error("Failed to route order {} to CME: {}", order.orderId, e.getMessage());
            rejectOrder(order, "CME routing failed", globalSequence);
        }
    }

    /**
     * Route order to Nasdaq
     */
    private void routeToNasdaq(SmartOrderRouter.OrderRequest order, long globalSequence) {
        // TODO: Implement Nasdaq routing
        log.info("Routing order {} to Nasdaq: {} {} {}@{}",
                order.orderId, order.side, order.symbol, order.quantity, order.price);

        ExecutionReport execution = new ExecutionReport(
            generateExecutionId(),
            order.orderId,
            order.symbol,
            order.side,
            0, 0,
            ExecutionType.ROUTED_EXTERNAL,
            globalSequence,
            System.nanoTime()
        );
        execution.targetVenue = SmartOrderRouter.VenueType.NASDAQ;
        publishExecutionToOSM(execution);
    }

    /**
     * Route order to NYSE
     */
    private void routeToNYSE(SmartOrderRouter.OrderRequest order, long globalSequence) {
        // TODO: Implement NYSE routing
        log.info("Routing order {} to NYSE: {} {} {}@{}",
                order.orderId, order.side, order.symbol, order.quantity, order.price);

        ExecutionReport execution = new ExecutionReport(
            generateExecutionId(),
            order.orderId,
            order.symbol,
            order.side,
            0, 0,
            ExecutionType.ROUTED_EXTERNAL,
            globalSequence,
            System.nanoTime()
        );
        execution.targetVenue = SmartOrderRouter.VenueType.NYSE;
        publishExecutionToOSM(execution);
    }

    /**
     * Reject order
     */
    private void rejectOrder(SmartOrderRouter.OrderRequest order, String reason, long globalSequence) {
        log.info("Rejecting order {}: {}", order.orderId, reason);

        ExecutionReport execution = new ExecutionReport(
            generateExecutionId(),
            order.orderId,
            order.symbol,
            order.side,
            0, 0,
            ExecutionType.REJECTED,
            globalSequence,
            System.nanoTime()
        );
        execution.rejectReason = reason;
        publishExecutionToOSM(execution);
    }

    /**
     * Publish execution report back to OSM
     */
    private void publishExecutionToOSM(ExecutionReport execution) {
        try {
            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocate(256));
            int length = encodeExecutionReport(execution, buffer, 0);

            long result = executionPublication.offer(buffer, 0, length);
            if (result > 0) {
                executionsSent.incrementAndGet();
                log.debug("Published execution report to OSM: {}", execution.executionId);
            } else {
                log.warn("Failed to publish execution report: result={}", result);
            }

        } catch (Exception e) {
            log.error("Error publishing execution report: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle cluster egress messages (global coordination)
     */
    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer,
                         int offset, int length, Header header) {
        try {
            long globalSequence = header.position();

            // Handle cluster coordination messages
            // TODO: Implement cluster message handling if needed
            log.debug("Received cluster message: seq={}", globalSequence);

        } catch (Exception e) {
            log.error("Error handling cluster message: {}", e.getMessage(), e);
        }
    }

    // Helper methods

    private SmartOrderRouter.OrderRequest decodeOrderRequest(DirectBuffer buffer, int offset, int length) {
        try {
            // Simple decoding - in practice would use SBE or similar
            ByteBuffer byteBuffer = ByteBuffer.allocate(length);
            buffer.getBytes(offset, byteBuffer.array(), 0, length);
            byteBuffer.position(0);

            long orderId = byteBuffer.getLong();
            int symbolLength = byteBuffer.getInt();
            byte[] symbolBytes = new byte[symbolLength];
            byteBuffer.get(symbolBytes);
            String symbol = new String(symbolBytes);

            Side side = Side.values()[byteBuffer.getInt()];
            OrderType orderType = OrderType.values()[byteBuffer.getInt()];
            long price = byteBuffer.getLong();
            long quantity = byteBuffer.getLong();
            long timestamp = byteBuffer.getLong();

            return new SmartOrderRouter.OrderRequest(
                orderId, symbol, side, orderType, price, quantity, timestamp, "SOR"
            );

        } catch (Exception e) {
            log.error("Failed to decode order request: {}", e.getMessage());
            return null;
        }
    }

    private int encodeExecutionReport(ExecutionReport execution, UnsafeBuffer buffer, int offset) {
        // Simple encoding - in practice would use SBE or similar
        ByteBuffer byteBuffer = ByteBuffer.allocate(256);

        byteBuffer.putLong(execution.executionId);
        byteBuffer.putLong(execution.orderId);

        byte[] symbolBytes = execution.symbol.getBytes();
        byteBuffer.putInt(symbolBytes.length);
        byteBuffer.put(symbolBytes);

        byteBuffer.putInt(execution.side.ordinal());
        byteBuffer.putLong(execution.executedQuantity);
        byteBuffer.putLong(execution.executionPrice);
        byteBuffer.putInt(execution.executionType.ordinal());
        byteBuffer.putLong(execution.globalSequence);
        byteBuffer.putLong(execution.timestamp);

        int length = byteBuffer.position();
        buffer.putBytes(offset, byteBuffer.array(), 0, length);

        return length;
    }

    private final AtomicLong executionIdGenerator = new AtomicLong(1);
    private long generateExecutionId() {
        return executionIdGenerator.getAndIncrement();
    }

    private final AtomicLong childOrderIdGenerator = new AtomicLong(1);
    private long generateChildOrderId(long parentOrderId, int childIndex) {
        return parentOrderId * 1000 + childIndex; // Simple child ID generation
    }

    /**
     * Get service statistics
     */
    public ServiceStats getStatistics() {
        SmartOrderRouter.RoutingStats sorStats = smartOrderRouter.getStatistics();

        return new ServiceStats(
            processedOrders.get(),
            executionsSent.get(),
            sorStats.totalOrders,
            sorStats.internalRoutes,
            sorStats.externalRoutes,
            sorStats.rejectedOrders,
            sorStats.avgLatencyNanos
        );
    }

    // Supporting classes

    public static class ExecutionReport {
        public final long executionId;
        public final long orderId;
        public final String symbol;
        public final Side side;
        public final long executedQuantity;
        public final long executionPrice;
        public final ExecutionType executionType;
        public final long globalSequence;
        public final long timestamp;

        public SmartOrderRouter.VenueType targetVenue;
        public String rejectReason;

        public ExecutionReport(long executionId, long orderId, String symbol, Side side,
                              long executedQuantity, long executionPrice, ExecutionType executionType,
                              long globalSequence, long timestamp) {
            this.executionId = executionId;
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.executedQuantity = executedQuantity;
            this.executionPrice = executionPrice;
            this.executionType = executionType;
            this.globalSequence = globalSequence;
            this.timestamp = timestamp;
        }
    }

    public enum ExecutionType {
        ROUTED_INTERNAL,   // Routed back to OSM
        ROUTED_EXTERNAL,   // Routed to external venue
        PARTIAL_FILL,      // Partial execution
        FULL_FILL,         // Full execution
        REJECTED           // Order rejected
    }

    public static class ServiceStats {
        public final long ordersReceived;
        public final long executionsSent;
        public final long totalOrdersRouted;
        public final long internalRoutes;
        public final long externalRoutes;
        public final long rejectedOrders;
        public final long avgRoutingLatencyNanos;

        public ServiceStats(long ordersReceived, long executionsSent, long totalOrdersRouted,
                           long internalRoutes, long externalRoutes, long rejectedOrders,
                           long avgRoutingLatencyNanos) {
            this.ordersReceived = ordersReceived;
            this.executionsSent = executionsSent;
            this.totalOrdersRouted = totalOrdersRouted;
            this.internalRoutes = internalRoutes;
            this.externalRoutes = externalRoutes;
            this.rejectedOrders = rejectedOrders;
            this.avgRoutingLatencyNanos = avgRoutingLatencyNanos;
        }

        @Override
        public String toString() {
            return String.format("SORServiceStats[received=%d, sent=%d, routed=%d, internal=%d, external=%d, rejected=%d, avgLatency=%dns]",
                    ordersReceived, executionsSent, totalOrdersRouted, internalRoutes, externalRoutes, rejectedOrders, avgRoutingLatencyNanos);
        }
    }
}
