package com.microoptimus.osm.sor;

/**
 * VenueScore - Multi-factor venue scoring result
 *
 * Used by VenueScorer to rank venues for routing decisions.
 */
public class VenueScore {

    public VenueId venueId;
    public double totalScore;
    public long maxQuantity;

    // Component scores (0.0-1.0)
    public double priorityScore;
    public double latencyScore;
    public double fillRateScore;
    public double costScore;
    public double liquidityScore;

    // Estimated execution metrics
    public long estimatedLatencyNanos;
    public double estimatedFillProbability;
    public double estimatedCost;

    public VenueScore() {}

    /**
     * Initialize score for pooling
     */
    public void init(VenueId venueId) {
        this.venueId = venueId;
        this.totalScore = 0;
        this.maxQuantity = 0;
        this.priorityScore = 0;
        this.latencyScore = 0;
        this.fillRateScore = 0;
        this.costScore = 0;
        this.liquidityScore = 0;
        this.estimatedLatencyNanos = 0;
        this.estimatedFillProbability = 0;
        this.estimatedCost = 0;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.venueId = null;
        this.totalScore = 0;
        this.maxQuantity = 0;
        this.priorityScore = 0;
        this.latencyScore = 0;
        this.fillRateScore = 0;
        this.costScore = 0;
        this.liquidityScore = 0;
        this.estimatedLatencyNanos = 0;
        this.estimatedFillProbability = 0;
        this.estimatedCost = 0;
    }

    /**
     * Calculate total score from components
     */
    public void calculateTotalScore(double priorityWeight, double latencyWeight,
                                     double fillRateWeight, double costWeight,
                                     double liquidityWeight) {
        this.totalScore = (priorityScore * priorityWeight) +
                          (latencyScore * latencyWeight) +
                          (fillRateScore * fillRateWeight) +
                          (costScore * costWeight) +
                          (liquidityScore * liquidityWeight);
    }

    @Override
    public String toString() {
        return String.format("VenueScore[%s, total=%.3f, priority=%.2f, latency=%.2f, fillRate=%.2f, cost=%.2f]",
                venueId, totalScore, priorityScore, latencyScore, fillRateScore, costScore);
    }
}
