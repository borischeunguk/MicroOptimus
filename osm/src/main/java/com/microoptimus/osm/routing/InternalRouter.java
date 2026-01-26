package com.microoptimus.osm.routing;

import com.microoptimus.osm.sor.OrderRequest;
import com.microoptimus.osm.sor.RoutingDecision;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InternalRouter - Routes orders to the internaliser for internal matching
 *
 * Uses Aeron or shared memory to communicate with the internaliser module.
 */
public class InternalRouter {

    private static final Logger log = LoggerFactory.getLogger(InternalRouter.class);

    // Message buffer for encoding
    private final MutableDirectBuffer buffer;

    // Callback for routing results
    private InternalRoutingCallback callback;

    // Statistics
    private long ordersRouted;
    private long fillsReceived;
    private long partialFillsReceived;
    private long rejectsReceived;

    // State
    private volatile boolean connected;

    public InternalRouter() {
        this.buffer = new ExpandableDirectByteBuffer(256);
    }

    /**
     * Set callback for routing results
     */
    public void setCallback(InternalRoutingCallback callback) {
        this.callback = callback;
    }

    /**
     * Connect to the internaliser
     */
    public boolean connect() {
        // In production, this would establish Aeron connection or shared memory mapping
        this.connected = true;
        log.info("InternalRouter connected to internaliser");
        return true;
    }

    /**
     * Disconnect from the internaliser
     */
    public void disconnect() {
        this.connected = false;
        log.info("InternalRouter disconnected from internaliser");
    }

    /**
     * Route order to internaliser
     */
    public boolean routeOrder(OrderRequest request, RoutingDecision decision) {
        if (!connected) {
            log.warn("Cannot route order - not connected to internaliser");
            return false;
        }

        ordersRouted++;

        // Encode order for transmission (would use SBE in production)
        int offset = 0;
        buffer.putInt(offset, 1); // Message type: NewOrder
        offset += 4;
        buffer.putLong(offset, request.sequenceId);
        offset += 8;
        buffer.putLong(offset, request.orderId);
        offset += 8;
        buffer.putInt(offset, request.symbolIndex);
        offset += 4;
        buffer.putInt(offset, request.side.ordinal());
        offset += 4;
        buffer.putLong(offset, request.price);
        offset += 8;
        buffer.putLong(offset, decision.getQuantity());
        offset += 8;
        buffer.putInt(offset, request.timeInForce.ordinal());
        offset += 4;
        buffer.putLong(offset, request.clientId);
        offset += 8;
        buffer.putInt(offset, request.flowType.ordinal());
        offset += 4;
        buffer.putLong(offset, request.parentOrderId);

        // In production, this would send via Aeron publication
        // For now, just log
        log.debug("Routed order {} to internaliser, qty={}", request.orderId, decision.getQuantity());

        return true;
    }

    /**
     * Route cancel request to internaliser
     */
    public boolean routeCancelOrder(long orderId, int symbolIndex) {
        if (!connected) {
            return false;
        }

        int offset = 0;
        buffer.putInt(offset, 2); // Message type: CancelOrder
        offset += 4;
        buffer.putLong(offset, orderId);
        offset += 8;
        buffer.putInt(offset, symbolIndex);

        log.debug("Routed cancel for order {} to internaliser", orderId);
        return true;
    }

    /**
     * Handle response from internaliser (called by Aeron subscription handler)
     */
    public void onInternaliserResponse(DirectBuffer buffer, int offset, int length) {
        int messageType = buffer.getInt(offset);
        offset += 4;

        switch (messageType) {
            case 101: // MatchResult
                handleMatchResult(buffer, offset);
                break;
            case 102: // CancelResult
                handleCancelResult(buffer, offset);
                break;
            default:
                log.warn("Unknown internaliser response type: {}", messageType);
        }
    }

    private void handleMatchResult(DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset);
        offset += 8;
        int status = buffer.getInt(offset);
        offset += 4;
        long executedQty = buffer.getLong(offset);
        offset += 8;
        long leavesQty = buffer.getLong(offset);
        offset += 8;
        long avgPrice = buffer.getLong(offset);

        if (executedQty > 0) {
            if (leavesQty == 0) {
                fillsReceived++;
            } else {
                partialFillsReceived++;
            }
        }

        if (callback != null) {
            callback.onMatchResult(orderId, status, executedQty, leavesQty, avgPrice);
        }
    }

    private void handleCancelResult(DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset);
        offset += 8;
        int status = buffer.getInt(offset);
        offset += 4;
        long cancelledQty = buffer.getLong(offset);

        if (callback != null) {
            callback.onCancelResult(orderId, status, cancelledQty);
        }
    }

    // Statistics
    public long getOrdersRouted() { return ordersRouted; }
    public long getFillsReceived() { return fillsReceived; }
    public long getPartialFillsReceived() { return partialFillsReceived; }
    public long getRejectsReceived() { return rejectsReceived; }
    public boolean isConnected() { return connected; }

    /**
     * Callback interface for internal routing results
     */
    public interface InternalRoutingCallback {
        void onMatchResult(long orderId, int status, long executedQty, long leavesQty, long avgPrice);
        void onCancelResult(long orderId, int status, long cancelledQty);
    }
}
