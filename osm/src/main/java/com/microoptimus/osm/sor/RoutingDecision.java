package com.microoptimus.osm.sor;

/**
 * RoutingDecision - SOR routing decision result
 *
 * Represents where an order should be routed.
 */
public class RoutingDecision {

    public enum RoutingAction {
        ROUTE_INTERNAL,   // Route to internaliser
        ROUTE_EXTERNAL,   // Route to single external venue
        SPLIT_ORDER,      // Split across multiple venues
        REJECT            // Reject the order
    }

    private final long orderId;
    private final RoutingAction action;
    private final VenueId primaryVenue;
    private final VenueAllocation[] allocations;
    private final long quantity;
    private final String rejectReason;

    // Estimated metrics
    private long estimatedFillTimeNanos;
    private double confidence;

    // Private constructors - use factory methods
    private RoutingDecision(long orderId, RoutingAction action, VenueId venue,
                            long quantity, VenueAllocation[] allocations, String rejectReason) {
        this.orderId = orderId;
        this.action = action;
        this.primaryVenue = venue;
        this.quantity = quantity;
        this.allocations = allocations;
        this.rejectReason = rejectReason;
    }

    // Factory methods

    /**
     * Create internal routing decision
     */
    public static RoutingDecision internal(long orderId, long quantity) {
        return new RoutingDecision(orderId, RoutingAction.ROUTE_INTERNAL,
                VenueId.INTERNAL, quantity, null, null);
    }

    /**
     * Create external routing decision
     */
    public static RoutingDecision external(long orderId, VenueId venue, long quantity) {
        return new RoutingDecision(orderId, RoutingAction.ROUTE_EXTERNAL,
                venue, quantity, null, null);
    }

    /**
     * Create split order decision
     */
    public static RoutingDecision split(long orderId, VenueAllocation[] allocations) {
        long totalQty = 0;
        VenueId primary = null;
        for (VenueAllocation alloc : allocations) {
            totalQty += alloc.quantity;
            if (alloc.priority == 1) {
                primary = alloc.venueId;
            }
        }
        if (primary == null && allocations.length > 0) {
            primary = allocations[0].venueId;
        }
        return new RoutingDecision(orderId, RoutingAction.SPLIT_ORDER,
                primary, totalQty, allocations, null);
    }

    /**
     * Create rejection decision
     */
    public static RoutingDecision rejected(long orderId, String reason) {
        return new RoutingDecision(orderId, RoutingAction.REJECT,
                null, 0, null, reason);
    }

    // Getters
    public long getOrderId() { return orderId; }
    public RoutingAction getAction() { return action; }
    public VenueId getPrimaryVenue() { return primaryVenue; }
    public VenueAllocation[] getAllocations() { return allocations; }
    public long getQuantity() { return quantity; }
    public String getRejectReason() { return rejectReason; }
    public long getEstimatedFillTimeNanos() { return estimatedFillTimeNanos; }
    public double getConfidence() { return confidence; }

    // Setters for estimates
    public void setEstimatedFillTimeNanos(long time) { this.estimatedFillTimeNanos = time; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    // Convenience methods
    public boolean isInternal() { return action == RoutingAction.ROUTE_INTERNAL; }
    public boolean isExternal() { return action == RoutingAction.ROUTE_EXTERNAL; }
    public boolean isSplit() { return action == RoutingAction.SPLIT_ORDER; }
    public boolean isRejected() { return action == RoutingAction.REJECT; }

    @Override
    public String toString() {
        if (isRejected()) {
            return String.format("RoutingDecision[orderId=%d, REJECT: %s]", orderId, rejectReason);
        }
        return String.format("RoutingDecision[orderId=%d, %s -> %s, qty=%d]",
                orderId, action, primaryVenue, quantity);
    }
}
