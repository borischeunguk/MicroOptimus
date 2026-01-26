package com.microoptimus.osm.cluster;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;
import com.microoptimus.osm.sor.*;
import com.microoptimus.osm.routing.InternalRouter;
import com.microoptimus.osm.routing.ExternalRouter;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSMClusterService - Aeron Cluster service for Smart Order Routing
 *
 * Responsibilities:
 * - Receives sequenced orders from cluster (DMA, algo slices, principal quotes)
 * - Routes through SOR for venue selection
 * - Dispatches to InternalRouter or ExternalRouter
 * - Publishes routing results back to cluster
 *
 * All order flows converge here:
 * - DMA orders from Gateway
 * - Algo slices from Algo Engine
 * - Principal quotes from Signal
 */
public class OSMClusterService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(OSMClusterService.class);

    // Core components
    private final SmartOrderRouter sor;
    private final InternalRouter internalRouter;
    private final ExternalRouter externalRouter;

    // Request pool (GC-free)
    private final OrderRequest[] requestPool;
    private int requestPoolIndex;
    private static final int REQUEST_POOL_SIZE = 1024;

    // Response buffer
    private final MutableDirectBuffer responseBuffer;

    // Cluster state
    private Cluster cluster;
    private long lastSequenceNumber;

    // Statistics
    private long ordersReceived;
    private long internalRoutes;
    private long externalRoutes;
    private long rejects;

    public OSMClusterService() {
        this.sor = new SmartOrderRouter();
        this.internalRouter = new InternalRouter();
        this.externalRouter = new ExternalRouter();
        this.responseBuffer = new ExpandableDirectByteBuffer(256);

        // Initialize request pool
        this.requestPool = new OrderRequest[REQUEST_POOL_SIZE];
        for (int i = 0; i < REQUEST_POOL_SIZE; i++) {
            requestPool[i] = new OrderRequest();
        }
        this.requestPoolIndex = 0;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        log.info("OSM Cluster Service started, role={}", cluster.role());

        // Initialize SOR
        sor.initialize();

        // Connect routers
        internalRouter.connect();
        externalRouter.connect();

        // Setup callbacks
        setupRouterCallbacks();

        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.debug("Session opened: sessionId={}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.debug("Session closed: sessionId={}, reason={}", session.id(), closeReason);
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp,
                                  DirectBuffer buffer, int offset, int length,
                                  Header header) {
        long startTime = System.nanoTime();

        try {
            int messageType = buffer.getInt(offset);
            offset += 4;

            switch (messageType) {
                case 1: // OrderRequest (DMA/Algo/Principal)
                    handleOrderRequest(session, timestamp, buffer, offset, header);
                    break;
                case 2: // CancelRequest
                    handleCancelRequest(session, timestamp, buffer, offset);
                    break;
                case 3: // ModifyRequest
                    handleModifyRequest(session, timestamp, buffer, offset);
                    break;
                default:
                    log.warn("Unknown message type: {}", messageType);
            }
        } finally {
            long latency = System.nanoTime() - startTime;
            if (latency > 500_000) { // Log if > 500μs
                log.warn("OSM processing took {}ns", latency);
            }
        }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        log.info("Taking OSM snapshot...");
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("OSM role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("OSM Cluster Service terminating");
        internalRouter.disconnect();
        externalRouter.disconnect();
    }

    /**
     * Handle incoming order request
     */
    private void handleOrderRequest(ClientSession session, long timestamp,
                                     DirectBuffer buffer, int offset, Header header) {
        // Get request from pool
        OrderRequest request = acquireRequest();

        // Decode request (simplified - would use SBE)
        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;
        long clientId = buffer.getLong(offset); offset += 8;
        long parentOrderId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        int sideOrdinal = buffer.getInt(offset); offset += 4;
        int orderTypeOrdinal = buffer.getInt(offset); offset += 4;
        long price = buffer.getLong(offset); offset += 8;
        long quantity = buffer.getLong(offset); offset += 8;
        int tifOrdinal = buffer.getInt(offset); offset += 4;
        int flowTypeOrdinal = buffer.getInt(offset);

        // Initialize request
        request.init(sequenceId, orderId, clientId, parentOrderId, symbolIndex,
                Side.values()[sideOrdinal],
                OrderType.values()[orderTypeOrdinal],
                price, quantity,
                TimeInForce.values()[tifOrdinal],
                timestamp,
                OrderRequest.OrderFlowType.values()[flowTypeOrdinal]);

        ordersReceived++;
        lastSequenceNumber = sequenceId;

        // Route through SOR
        RoutingDecision decision = sor.routeOrder(request);

        // Dispatch to appropriate router
        dispatchRouting(request, decision);

        // Send response
        sendRoutingResponse(session, decision);

        // Return request to pool
        releaseRequest(request);
    }

    /**
     * Dispatch routing decision to appropriate router
     */
    private void dispatchRouting(OrderRequest request, RoutingDecision decision) {
        switch (decision.getAction()) {
            case ROUTE_INTERNAL:
                internalRouter.routeOrder(request, decision);
                internalRoutes++;
                break;

            case ROUTE_EXTERNAL:
                externalRouter.routeOrder(request, decision);
                externalRoutes++;
                break;

            case SPLIT_ORDER:
                // Route internal portion
                for (VenueAllocation alloc : decision.getAllocations()) {
                    if (alloc.venueId == VenueId.INTERNAL) {
                        RoutingDecision internalDecision = RoutingDecision.internal(
                                request.orderId, alloc.quantity);
                        internalRouter.routeOrder(request, internalDecision);
                        internalRoutes++;
                    }
                }
                // Route external portions
                externalRouter.routeSplitOrder(request, decision);
                externalRoutes++;
                break;

            case REJECT:
                rejects++;
                log.debug("Order {} rejected: {}", request.orderId, decision.getRejectReason());
                break;
        }
    }

    /**
     * Handle cancel request
     */
    private void handleCancelRequest(ClientSession session, long timestamp,
                                      DirectBuffer buffer, int offset) {
        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        int venueOrdinal = buffer.getInt(offset);

        lastSequenceNumber = sequenceId;

        VenueId venue = VenueId.values()[venueOrdinal];
        if (venue == VenueId.INTERNAL) {
            internalRouter.routeCancelOrder(orderId, symbolIndex);
        } else {
            externalRouter.routeCancelOrder(orderId, venue);
        }
    }

    /**
     * Handle modify request
     */
    private void handleModifyRequest(ClientSession session, long timestamp,
                                      DirectBuffer buffer, int offset) {
        // Cancel and replace - simplified implementation
        handleCancelRequest(session, timestamp, buffer, offset);
        // Would then send new order...
    }

    /**
     * Send routing response to client
     */
    private void sendRoutingResponse(ClientSession session, RoutingDecision decision) {
        if (session == null) return;

        int offset = 0;
        responseBuffer.putInt(offset, 100); // Response type
        offset += 4;
        responseBuffer.putLong(offset, decision.getOrderId());
        offset += 8;
        responseBuffer.putInt(offset, decision.getAction().ordinal());
        offset += 4;
        responseBuffer.putInt(offset, decision.getPrimaryVenue() != null ?
                decision.getPrimaryVenue().ordinal() : 0);
        offset += 4;
        responseBuffer.putLong(offset, decision.getQuantity());

        // Would send via session.offer() in real implementation
    }

    /**
     * Setup callbacks for router responses
     */
    private void setupRouterCallbacks() {
        internalRouter.setCallback(new InternalRouter.InternalRoutingCallback() {
            @Override
            public void onMatchResult(long orderId, int status, long executedQty,
                                      long leavesQty, long avgPrice) {
                log.debug("Internal match: orderId={}, execQty={}, leaves={}",
                        orderId, executedQty, leavesQty);
            }

            @Override
            public void onCancelResult(long orderId, int status, long cancelledQty) {
                log.debug("Internal cancel: orderId={}, cancelledQty={}",
                        orderId, cancelledQty);
            }
        });

        externalRouter.setCallback(new ExternalRouter.ExternalRoutingCallback() {
            @Override
            public void onExecutionReport(long orderId, VenueId venue, int execType,
                                         long execQty, long execPrice, long leavesQty, long cumQty) {
                log.debug("External exec: orderId={}, venue={}, execQty={}",
                        orderId, venue, execQty);
            }

            @Override
            public void onCancelAck(long orderId, VenueId venue, long cancelledQty) {
                log.debug("External cancel ack: orderId={}, venue={}", orderId, venue);
            }

            @Override
            public void onReject(long orderId, VenueId venue, int reasonCode) {
                log.debug("External reject: orderId={}, venue={}, reason={}",
                        orderId, venue, reasonCode);
            }
        });
    }

    /**
     * Load snapshot for recovery
     */
    private void loadSnapshot(Image snapshotImage) {
        log.info("Loading OSM snapshot...");
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
    public long getOrdersReceived() { return ordersReceived; }
    public long getInternalRoutes() { return internalRoutes; }
    public long getExternalRoutes() { return externalRoutes; }
    public long getRejects() { return rejects; }
    public long getLastSequenceNumber() { return lastSequenceNumber; }
    public SmartOrderRouter getSOR() { return sor; }
}
