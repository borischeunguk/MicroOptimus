package com.microoptimus.osm.sor;

/**
 * VenueAllocation - Allocation of order quantity to a venue
 *
 * Used when orders are split across multiple venues.
 */
public class VenueAllocation {

    public VenueId venueId;
    public long quantity;
    public int priority;  // Execution priority (1 = first)
    public long estimatedLatencyNanos;
    public double estimatedFillProbability;
    public double estimatedCost;

    public VenueAllocation() {}

    public VenueAllocation(VenueId venueId, long quantity, int priority) {
        this.venueId = venueId;
        this.quantity = quantity;
        this.priority = priority;
    }

    /**
     * Initialize for pooling
     */
    public void init(VenueId venueId, long quantity, int priority,
                     long latency, double fillProb, double cost) {
        this.venueId = venueId;
        this.quantity = quantity;
        this.priority = priority;
        this.estimatedLatencyNanos = latency;
        this.estimatedFillProbability = fillProb;
        this.estimatedCost = cost;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.venueId = null;
        this.quantity = 0;
        this.priority = 0;
        this.estimatedLatencyNanos = 0;
        this.estimatedFillProbability = 0;
        this.estimatedCost = 0;
    }

    @Override
    public String toString() {
        return String.format("Allocation[%s: %d, priority=%d]", venueId, quantity, priority);
    }
}
