package com.microoptimus.osm.sor;

/**
 * VenueScorer - Multi-factor venue scoring algorithm
 *
 * Scores venues based on:
 * - Priority (base priority configuration)
 * - Latency (execution speed)
 * - Fill rate (historical fill probability)
 * - Cost (fees and market impact)
 * - Liquidity (available quantity)
 */
public class VenueScorer {

    // Scoring weights (should sum to 1.0)
    private double priorityWeight = 0.25;
    private double latencyWeight = 0.25;
    private double fillRateWeight = 0.25;
    private double costWeight = 0.15;
    private double liquidityWeight = 0.10;

    // Normalization constants
    private static final long MAX_LATENCY_NANOS = 1_000_000; // 1ms
    private static final double MAX_COST_PER_SHARE = 0.001;  // 0.1%

    // Pre-allocated score objects (GC-free)
    private final VenueScore[] scores;

    public VenueScorer() {
        this.scores = new VenueScore[VenueId.values().length];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = new VenueScore();
        }
    }

    /**
     * Score all venues for the given order
     */
    public VenueScore[] scoreVenues(OrderRequest request, VenueConfig[] configs,
                                     boolean hasInternalLiquidity) {
        for (int i = 0; i < scores.length; i++) {
            VenueConfig config = configs[i];
            if (config == null || !config.isEnabled() || !config.isConnected()) {
                scores[i].reset();
                continue;
            }

            // Skip internal if no liquidity
            if (config.getVenueId() == VenueId.INTERNAL && !hasInternalLiquidity) {
                scores[i].reset();
                continue;
            }

            // Check if venue can handle the order
            if (!config.canHandle(request.quantity)) {
                scores[i].reset();
                continue;
            }

            scoreVenue(scores[i], request, config);
        }

        return scores;
    }

    /**
     * Score a single venue
     */
    private void scoreVenue(VenueScore score, OrderRequest request, VenueConfig config) {
        score.init(config.getVenueId());
        score.maxQuantity = config.getMaxOrderSize();

        // Priority score (0-100 normalized to 0-1)
        score.priorityScore = config.getPriority() / 100.0;

        // Latency score (lower is better)
        long latency = config.getLastLatencyNanos();
        score.latencyScore = 1.0 - Math.min(1.0, (double) latency / MAX_LATENCY_NANOS);
        score.estimatedLatencyNanos = latency;

        // Fill rate score
        score.fillRateScore = config.getRecentFillRate();
        score.estimatedFillProbability = config.getRecentFillRate();

        // Cost score (lower is better)
        double costPerShare = config.getFeesPerShare();
        score.costScore = 1.0 - Math.min(1.0, costPerShare / MAX_COST_PER_SHARE);
        score.estimatedCost = costPerShare * request.quantity;

        // Liquidity score (for internal, it's based on available liquidity)
        if (config.getVenueId() == VenueId.INTERNAL) {
            // Internal always scores high for liquidity when available
            score.liquidityScore = 1.0;
        } else {
            // External venues - assume adequate liquidity
            score.liquidityScore = 0.8;
        }

        // Calculate total score
        score.calculateTotalScore(priorityWeight, latencyWeight, fillRateWeight,
                                  costWeight, liquidityWeight);
    }

    /**
     * Set scoring weights
     */
    public void setWeights(double priority, double latency, double fillRate,
                           double cost, double liquidity) {
        this.priorityWeight = priority;
        this.latencyWeight = latency;
        this.fillRateWeight = fillRate;
        this.costWeight = cost;
        this.liquidityWeight = liquidity;
    }

    /**
     * Get current weights
     */
    public double[] getWeights() {
        return new double[] { priorityWeight, latencyWeight, fillRateWeight,
                              costWeight, liquidityWeight };
    }
}
