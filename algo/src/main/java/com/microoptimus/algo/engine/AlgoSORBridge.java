package com.microoptimus.algo.engine;

import com.microoptimus.algo.slice.Slice;
import com.microoptimus.osm.sor.OrderRequest;
import com.microoptimus.osm.sor.RoutingDecision;
import com.microoptimus.osm.sor.SmartOrderRouter;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * AlgoSORBridge - Bridges algo engine slices to the Smart Order Router
 *
 * Converts algo slices into SOR order requests with ALGO_SLICE flow type.
 */
public class AlgoSORBridge {

    private static final Logger log = LoggerFactory.getLogger(AlgoSORBridge.class);

    // SOR reference
    private SmartOrderRouter sor;

    // Order request pool (GC-free)
    private final OrderRequest[] requestPool;
    private int requestPoolIndex;
    private static final int REQUEST_POOL_SIZE = 256;

    // Sequence counter
    private long nextSequenceId = 1;

    // Callback for routing results
    private Consumer<RoutingResult> resultCallback;

    // Statistics
    private long slicesRouted;
    private long internalRoutes;
    private long externalRoutes;
    private long rejects;

    public AlgoSORBridge() {
        this.requestPool = new OrderRequest[REQUEST_POOL_SIZE];
        for (int i = 0; i < REQUEST_POOL_SIZE; i++) {
            requestPool[i] = new OrderRequest();
        }
        this.requestPoolIndex = 0;
    }

    /**
     * Set the SOR instance
     */
    public void setSOR(SmartOrderRouter sor) {
        this.sor = sor;
    }

    /**
     * Set callback for routing results
     */
    public void setResultCallback(Consumer<RoutingResult> callback) {
        this.resultCallback = callback;
    }

    /**
     * Route a slice through the SOR
     */
    public RoutingDecision routeSlice(Slice slice) {
        if (sor == null) {
            log.error("SOR not configured");
            return RoutingDecision.rejected(slice.getSliceId(), "SOR not configured");
        }

        // Convert slice to order request
        OrderRequest request = acquireRequest();
        request.init(
                nextSequenceId++,
                slice.getSliceId(),
                0, // clientId from parent order (not stored in slice)
                slice.getParentAlgoOrderId(),
                slice.getSymbolIndex(),
                slice.getSide(),
                com.microoptimus.common.types.OrderType.LIMIT,
                slice.getPrice(),
                slice.getQuantity(),
                com.microoptimus.common.types.TimeInForce.IOC, // Algo slices typically IOC
                slice.getCreateTimestamp(),
                OrderRequest.OrderFlowType.ALGO_SLICE
        );

        // Route through SOR
        RoutingDecision decision = sor.routeOrder(request);

        // Update statistics
        slicesRouted++;
        switch (decision.getAction()) {
            case ROUTE_INTERNAL -> internalRoutes++;
            case ROUTE_EXTERNAL, SPLIT_ORDER -> externalRoutes++;
            case REJECT -> rejects++;
        }

        // Notify callback
        if (resultCallback != null) {
            resultCallback.accept(new RoutingResult(slice.getSliceId(), slice.getParentAlgoOrderId(), decision));
        }

        // Return request to pool
        releaseRequest(request);

        log.debug("Slice {} routed: {}", slice.getSliceId(), decision);
        return decision;
    }

    /**
     * Batch route multiple slices
     */
    public void routeSlices(Iterable<Slice> slices) {
        for (Slice slice : slices) {
            routeSlice(slice);
        }
    }

    // Pool management
    private OrderRequest acquireRequest() {
        OrderRequest request = requestPool[requestPoolIndex];
        requestPoolIndex = (requestPoolIndex + 1) % REQUEST_POOL_SIZE;
        return request;
    }

    private void releaseRequest(OrderRequest request) {
        request.reset();
    }

    // Statistics
    public long getSlicesRouted() { return slicesRouted; }
    public long getInternalRoutes() { return internalRoutes; }
    public long getExternalRoutes() { return externalRoutes; }
    public long getRejects() { return rejects; }

    /**
     * Routing result for callback
     */
    public static class RoutingResult {
        public final long sliceId;
        public final long parentAlgoOrderId;
        public final RoutingDecision decision;

        public RoutingResult(long sliceId, long parentAlgoOrderId, RoutingDecision decision) {
            this.sliceId = sliceId;
            this.parentAlgoOrderId = parentAlgoOrderId;
            this.decision = decision;
        }
    }
}
