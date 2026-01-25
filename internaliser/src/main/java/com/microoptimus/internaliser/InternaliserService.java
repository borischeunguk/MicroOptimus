package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
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
import org.agrona.collections.Int2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InternaliserService - Aeron Cluster service for internal order matching
 *
 * Responsibilities:
 * - Receives sequenced orders from cluster
 * - Dispatches to appropriate orderbook by symbol
 * - Publishes execution reports back to cluster
 */
public class InternaliserService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(InternaliserService.class);

    // Shared pools across all orderbooks
    private final OrderPool orderPool;
    private final PriceLevelPool priceLevelPool;
    private final ExecutionReportPool execReportPool;

    // Orderbook per symbol (keyed by symbol index)
    private final Int2ObjectHashMap<InternalMatchingEngine> engines;

    // Response buffer
    private final MutableDirectBuffer responseBuffer;

    // Cluster state
    private Cluster cluster;
    private long lastSequenceNumber;

    // Statistics
    private final InternaliserStats stats;

    public InternaliserService() {
        this.orderPool = new OrderPool();
        this.priceLevelPool = new PriceLevelPool();
        this.execReportPool = new ExecutionReportPool();
        this.engines = new Int2ObjectHashMap<>();
        this.responseBuffer = new ExpandableDirectByteBuffer(256);
        this.stats = new InternaliserStats();
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        log.info("Internaliser service started, role={}", cluster.role());

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
            // Parse message header (simplified - would use SBE in production)
            int messageType = buffer.getInt(offset);
            offset += 4;

            switch (messageType) {
                case 1: // NewOrder
                    handleNewOrder(session, timestamp, buffer, offset);
                    break;
                case 2: // CancelOrder
                    handleCancelOrder(session, timestamp, buffer, offset);
                    break;
                default:
                    log.warn("Unknown message type: {}", messageType);
            }
        } finally {
            long latency = System.nanoTime() - startTime;
            stats.recordLatency(latency);
        }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events (e.g., order expiration for DAY orders)
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // Snapshot orderbook state for recovery
        log.info("Taking snapshot...");
        // Would serialize all orderbook state here
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("Internaliser service terminating");
    }

    /**
     * Handle new order message
     */
    private void handleNewOrder(ClientSession session, long timestamp,
                                DirectBuffer buffer, int offset) {
        // Parse order fields (simplified - would use SBE)
        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        int sideOrdinal = buffer.getInt(offset); offset += 4;
        long price = buffer.getLong(offset); offset += 8;
        long quantity = buffer.getLong(offset); offset += 8;
        int tifOrdinal = buffer.getInt(offset); offset += 4;
        long clientId = buffer.getLong(offset); offset += 8;
        int flowTypeOrdinal = buffer.getInt(offset); offset += 4;
        long parentOrderId = buffer.getLong(offset);

        Side side = Side.values()[sideOrdinal];
        TimeInForce tif = TimeInForce.values()[tifOrdinal];
        Order.OrderFlowType flowType = Order.OrderFlowType.values()[flowTypeOrdinal];

        // Get or create matching engine for symbol
        InternalMatchingEngine engine = getOrCreateEngine(symbolIndex);

        // Process order
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                orderId, clientId, parentOrderId, side, price, quantity,
                tif, timestamp, flowType);

        lastSequenceNumber = sequenceId;
        stats.recordOrder(result);

        // Send response (simplified)
        sendMatchResult(session, result);
    }

    /**
     * Handle cancel order message
     */
    private void handleCancelOrder(ClientSession session, long timestamp,
                                   DirectBuffer buffer, int offset) {
        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset);

        InternalMatchingEngine engine = engines.get(symbolIndex);
        if (engine == null) {
            // Order not found - symbol has no orderbook
            sendCancelResult(session, new InternalMatchingEngine.CancelResult(
                    orderId, InternalMatchingEngine.CancelResult.Status.NOT_FOUND, 0));
            return;
        }

        InternalMatchingEngine.CancelResult result = engine.cancelOrder(orderId, timestamp);
        lastSequenceNumber = sequenceId;
        stats.recordCancel(result);

        sendCancelResult(session, result);
    }

    /**
     * Get or create matching engine for symbol
     */
    private InternalMatchingEngine getOrCreateEngine(int symbolIndex) {
        InternalMatchingEngine engine = engines.get(symbolIndex);
        if (engine == null) {
            InternalOrderBook orderBook = new InternalOrderBook(symbolIndex, orderPool, priceLevelPool);
            engine = new InternalMatchingEngine(orderBook, execReportPool);
            engines.put(symbolIndex, engine);
            log.info("Created matching engine for symbol index: {}", symbolIndex);
        }
        return engine;
    }

    /**
     * Send match result response
     */
    private void sendMatchResult(ClientSession session, InternalMatchingEngine.MatchResult result) {
        if (session == null) return;

        // Encode response (simplified - would use SBE)
        int offset = 0;
        responseBuffer.putInt(offset, 101); offset += 4; // MatchResult type
        responseBuffer.putLong(offset, result.getOrderId()); offset += 8;
        responseBuffer.putInt(offset, result.getStatus().ordinal()); offset += 4;
        responseBuffer.putLong(offset, result.getExecutedQuantity()); offset += 8;
        responseBuffer.putLong(offset, result.getLeavesQuantity()); offset += 8;
        responseBuffer.putLong(offset, result.getAvgFillPrice()); offset += 8;

        // Would send via session.offer() in real implementation
    }

    /**
     * Send cancel result response
     */
    private void sendCancelResult(ClientSession session, InternalMatchingEngine.CancelResult result) {
        if (session == null) return;

        // Encode response (simplified - would use SBE)
        int offset = 0;
        responseBuffer.putInt(offset, 102); offset += 4; // CancelResult type
        responseBuffer.putLong(offset, result.getOrderId()); offset += 8;
        responseBuffer.putInt(offset, result.getStatus().ordinal()); offset += 4;
        responseBuffer.putLong(offset, result.getCancelledQuantity());
    }

    /**
     * Load snapshot for recovery
     */
    private void loadSnapshot(Image snapshotImage) {
        log.info("Loading snapshot...");
        // Would deserialize orderbook state here
    }

    // Accessors for testing
    public InternalMatchingEngine getEngine(int symbolIndex) {
        return engines.get(symbolIndex);
    }

    public InternaliserStats getStats() {
        return stats;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }
}
