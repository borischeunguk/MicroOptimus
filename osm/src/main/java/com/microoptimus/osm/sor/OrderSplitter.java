package com.microoptimus.osm.sor;

import java.util.Arrays;

/**
 * OrderSplitter - Splits large orders across multiple venues
 *
 * Strategies:
 * - Pro-rata: Split proportionally by venue score
 * - Sequential: Fill best venue first, then next
 * - Balanced: Equal split across top venues
 */
public class OrderSplitter {

    public enum SplitStrategy {
        PRO_RATA,       // Proportional to venue scores
        SEQUENTIAL,     // Best venue first
        BALANCED        // Equal split
    }

    private SplitStrategy strategy = SplitStrategy.PRO_RATA;
    private int maxVenues = 3;  // Maximum venues to split across

    // Pre-allocated allocations (GC-free)
    private final VenueAllocation[] allocations;

    public OrderSplitter() {
        this.allocations = new VenueAllocation[VenueId.values().length];
        for (int i = 0; i < allocations.length; i++) {
            allocations[i] = new VenueAllocation();
        }
    }

    /**
     * Split order across venues based on scores
     */
    public VenueAllocation[] splitOrder(OrderRequest request, VenueScore[] scores,
                                         VenueConfig[] configs) {
        // Sort scores by total score (descending)
        VenueScore[] sortedScores = sortByScore(scores);

        // Count valid venues
        int validVenues = 0;
        for (VenueScore score : sortedScores) {
            if (score != null && score.totalScore > 0) {
                validVenues++;
            }
        }

        if (validVenues == 0) {
            return new VenueAllocation[0];
        }

        int venuesToUse = Math.min(validVenues, maxVenues);
        VenueAllocation[] result = new VenueAllocation[venuesToUse];

        switch (strategy) {
            case PRO_RATA:
                splitProRata(result, request, sortedScores, venuesToUse);
                break;
            case SEQUENTIAL:
                splitSequential(result, request, sortedScores, configs, venuesToUse);
                break;
            case BALANCED:
                splitBalanced(result, request, sortedScores, venuesToUse);
                break;
        }

        return result;
    }

    /**
     * Pro-rata split based on venue scores
     */
    private void splitProRata(VenueAllocation[] result, OrderRequest request,
                               VenueScore[] sortedScores, int venuesToUse) {
        // Calculate total score
        double totalScore = 0;
        for (int i = 0; i < venuesToUse; i++) {
            totalScore += sortedScores[i].totalScore;
        }

        // Allocate proportionally
        long remaining = request.quantity;
        for (int i = 0; i < venuesToUse; i++) {
            VenueScore score = sortedScores[i];
            double proportion = score.totalScore / totalScore;
            long qty;

            if (i == venuesToUse - 1) {
                // Last venue gets remainder
                qty = remaining;
            } else {
                qty = Math.min(remaining, (long) (request.quantity * proportion));
                qty = Math.min(qty, score.maxQuantity);
            }

            result[i] = new VenueAllocation();
            result[i].init(score.venueId, qty, i + 1,
                    score.estimatedLatencyNanos,
                    score.estimatedFillProbability,
                    score.estimatedCost * qty / request.quantity);

            remaining -= qty;
        }
    }

    /**
     * Sequential split - fill best venue up to max, then next
     */
    private void splitSequential(VenueAllocation[] result, OrderRequest request,
                                  VenueScore[] sortedScores, VenueConfig[] configs,
                                  int venuesToUse) {
        long remaining = request.quantity;

        for (int i = 0; i < venuesToUse && remaining > 0; i++) {
            VenueScore score = sortedScores[i];
            long maxQty = score.maxQuantity;
            long qty = Math.min(remaining, maxQty);

            result[i] = new VenueAllocation();
            result[i].init(score.venueId, qty, i + 1,
                    score.estimatedLatencyNanos,
                    score.estimatedFillProbability,
                    score.estimatedCost * qty / request.quantity);

            remaining -= qty;
        }
    }

    /**
     * Balanced split - equal allocation across venues
     */
    private void splitBalanced(VenueAllocation[] result, OrderRequest request,
                                VenueScore[] sortedScores, int venuesToUse) {
        long baseQty = request.quantity / venuesToUse;
        long remainder = request.quantity % venuesToUse;

        for (int i = 0; i < venuesToUse; i++) {
            VenueScore score = sortedScores[i];
            long qty = baseQty + (i < remainder ? 1 : 0);
            qty = Math.min(qty, score.maxQuantity);

            result[i] = new VenueAllocation();
            result[i].init(score.venueId, qty, i + 1,
                    score.estimatedLatencyNanos,
                    score.estimatedFillProbability,
                    score.estimatedCost * qty / request.quantity);
        }
    }

    /**
     * Sort scores by total score (descending)
     */
    private VenueScore[] sortByScore(VenueScore[] scores) {
        VenueScore[] sorted = Arrays.copyOf(scores, scores.length);
        Arrays.sort(sorted, (a, b) -> {
            if (a == null || a.totalScore == 0) return 1;
            if (b == null || b.totalScore == 0) return -1;
            return Double.compare(b.totalScore, a.totalScore);
        });
        return sorted;
    }

    // Configuration
    public void setStrategy(SplitStrategy strategy) { this.strategy = strategy; }
    public void setMaxVenues(int max) { this.maxVenues = max; }
    public SplitStrategy getStrategy() { return strategy; }
    public int getMaxVenues() { return maxVenues; }
}
