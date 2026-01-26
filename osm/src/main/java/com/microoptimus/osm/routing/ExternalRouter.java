package com.microoptimus.osm.routing;

import com.microoptimus.osm.sor.OrderRequest;
import com.microoptimus.osm.sor.RoutingDecision;
import com.microoptimus.osm.sor.VenueAllocation;
import com.microoptimus.osm.sor.VenueId;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExternalRouter - Routes orders to external venues via liquidator
 *
 * Communicates with the liquidator module which handles:
 * - CME (iLink3)
 * - NASDAQ (OUCH)
 * - NYSE (FIX)
 * - Other external venues
 */
public class ExternalRouter {

    private static final Logger log = LoggerFactory.getLogger(ExternalRouter.class);

    // Message buffer for encoding
    private final MutableDirectBuffer buffer;

    // Callback for routing results
    private ExternalRoutingCallback callback;

    // Statistics per venue
    private final long[] ordersRoutedByVenue;
    private final long[] fillsByVenue;
    private final long[] rejectsByVenue;

    // State
    private volatile boolean connected;

    public ExternalRouter() {
        this.buffer = new ExpandableDirectByteBuffer(512);
        int venueCount = VenueId.values().length;
        this.ordersRoutedByVenue = new long[venueCount];
        this.fillsByVenue = new long[venueCount];
        this.rejectsByVenue = new long[venueCount];
    }

    /**
     * Set callback for routing results
     */
    public void setCallback(ExternalRoutingCallback callback) {
        this.callback = callback;
    }

    /**
     * Connect to the liquidator
     */
    public boolean connect() {
        this.connected = true;
        log.info("ExternalRouter connected to liquidator");
        return true;
    }

    /**
     * Disconnect from the liquidator
     */
    public void disconnect() {
        this.connected = false;
        log.info("ExternalRouter disconnected from liquidator");
    }

    /**
     * Route order to single external venue
     */
    public boolean routeOrder(OrderRequest request, RoutingDecision decision) {
        if (!connected) {
            log.warn("Cannot route order - not connected to liquidator");
            return false;
        }

        VenueId venue = decision.getPrimaryVenue();
        if (venue == null || venue == VenueId.INTERNAL) {
            log.error("Invalid venue for external routing: {}", venue);
            return false;
        }

        ordersRoutedByVenue[venue.ordinal()]++;

        // Encode order for transmission to liquidator
        int offset = encodeOrderRequest(request, decision.getQuantity(), venue);

        log.debug("Routed order {} to {} via liquidator, qty={}",
                request.orderId, venue, decision.getQuantity());

        return true;
    }

    /**
     * Route split order to multiple venues
     */
    public boolean routeSplitOrder(OrderRequest request, RoutingDecision decision) {
        if (!connected) {
            return false;
        }

        VenueAllocation[] allocations = decision.getAllocations();
        if (allocations == null || allocations.length == 0) {
            return false;
        }

        for (VenueAllocation alloc : allocations) {
            if (alloc.venueId == VenueId.INTERNAL) {
                // Skip internal allocations - handled by InternalRouter
                continue;
            }

            ordersRoutedByVenue[alloc.venueId.ordinal()]++;

            // Encode child order
            int offset = encodeOrderRequest(request, alloc.quantity, alloc.venueId);

            log.debug("Routed split order {} to {} via liquidator, qty={}",
                    request.orderId, alloc.venueId, alloc.quantity);
        }

        return true;
    }

    /**
     * Route cancel request to external venue
     */
    public boolean routeCancelOrder(long orderId, VenueId venue) {
        if (!connected || venue == VenueId.INTERNAL) {
            return false;
        }

        int offset = 0;
        buffer.putInt(offset, 3); // Message type: CancelOrder
        offset += 4;
        buffer.putLong(offset, orderId);
        offset += 8;
        buffer.putInt(offset, venue.ordinal());

        log.debug("Routed cancel for order {} to {} via liquidator", orderId, venue);
        return true;
    }

    /**
     * Encode order request into buffer
     */
    private int encodeOrderRequest(OrderRequest request, long quantity, VenueId venue) {
        int offset = 0;
        buffer.putInt(offset, 1); // Message type: NewOrder
        offset += 4;
        buffer.putLong(offset, request.sequenceId);
        offset += 8;
        buffer.putLong(offset, request.orderId);
        offset += 8;
        buffer.putInt(offset, venue.ordinal());
        offset += 4;
        buffer.putInt(offset, request.symbolIndex);
        offset += 4;
        buffer.putInt(offset, request.side.ordinal());
        offset += 4;
        buffer.putInt(offset, request.orderType.ordinal());
        offset += 4;
        buffer.putLong(offset, request.price);
        offset += 8;
        buffer.putLong(offset, quantity);
        offset += 8;
        buffer.putInt(offset, request.timeInForce.ordinal());
        offset += 4;
        buffer.putLong(offset, request.clientId);
        offset += 8;
        buffer.putLong(offset, request.timestamp);

        return offset;
    }

    /**
     * Handle response from liquidator
     */
    public void onLiquidatorResponse(DirectBuffer buffer, int offset, int length) {
        int messageType = buffer.getInt(offset);
        offset += 4;

        switch (messageType) {
            case 201: // ExecutionReport
                handleExecutionReport(buffer, offset);
                break;
            case 202: // CancelAck
                handleCancelAck(buffer, offset);
                break;
            case 203: // Reject
                handleReject(buffer, offset);
                break;
            default:
                log.warn("Unknown liquidator response type: {}", messageType);
        }
    }

    private void handleExecutionReport(DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset);
        offset += 8;
        int venueOrdinal = buffer.getInt(offset);
        offset += 4;
        int execType = buffer.getInt(offset);
        offset += 4;
        long execQty = buffer.getLong(offset);
        offset += 8;
        long execPrice = buffer.getLong(offset);
        offset += 8;
        long leavesQty = buffer.getLong(offset);
        offset += 8;
        long cumQty = buffer.getLong(offset);

        VenueId venue = VenueId.values()[venueOrdinal];
        fillsByVenue[venueOrdinal]++;

        if (callback != null) {
            callback.onExecutionReport(orderId, venue, execType, execQty, execPrice, leavesQty, cumQty);
        }
    }

    private void handleCancelAck(DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset);
        offset += 8;
        int venueOrdinal = buffer.getInt(offset);
        offset += 4;
        long cancelledQty = buffer.getLong(offset);

        VenueId venue = VenueId.values()[venueOrdinal];

        if (callback != null) {
            callback.onCancelAck(orderId, venue, cancelledQty);
        }
    }

    private void handleReject(DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset);
        offset += 8;
        int venueOrdinal = buffer.getInt(offset);
        offset += 4;
        int reasonCode = buffer.getInt(offset);

        VenueId venue = VenueId.values()[venueOrdinal];
        rejectsByVenue[venueOrdinal]++;

        if (callback != null) {
            callback.onReject(orderId, venue, reasonCode);
        }
    }

    // Statistics
    public long getOrdersRouted(VenueId venue) {
        return ordersRoutedByVenue[venue.ordinal()];
    }

    public long getTotalOrdersRouted() {
        long total = 0;
        for (long count : ordersRoutedByVenue) {
            total += count;
        }
        return total;
    }

    public long getFills(VenueId venue) {
        return fillsByVenue[venue.ordinal()];
    }

    public long getRejects(VenueId venue) {
        return rejectsByVenue[venue.ordinal()];
    }

    public boolean isConnected() { return connected; }

    /**
     * Callback interface for external routing results
     */
    public interface ExternalRoutingCallback {
        void onExecutionReport(long orderId, VenueId venue, int execType,
                              long execQty, long execPrice, long leavesQty, long cumQty);
        void onCancelAck(long orderId, VenueId venue, long cancelledQty);
        void onReject(long orderId, VenueId venue, int reasonCode);
    }
}
