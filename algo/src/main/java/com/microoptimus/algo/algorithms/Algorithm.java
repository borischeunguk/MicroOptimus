package com.microoptimus.algo.algorithms;

import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.slice.Slice;

import java.util.List;

/**
 * Algorithm - Interface for algorithmic execution strategies
 */
public interface Algorithm {

    /**
     * Get the algorithm type name
     */
    String getName();

    /**
     * Initialize the algorithm for a new order
     */
    void initialize(AlgoOrder order);

    /**
     * Check if algorithm should generate a slice now
     */
    boolean shouldGenerateSlice(AlgoOrder order, long currentTime);

    /**
     * Generate the next slice(s) for the order
     * Returns list of slices to send (may be empty)
     */
    List<Slice> generateSlices(AlgoOrder order, long currentTime, long currentPrice);

    /**
     * Handle slice execution feedback
     */
    void onSliceExecution(AlgoOrder order, Slice slice, long execQty, long execPrice);

    /**
     * Handle slice completion (filled, cancelled, rejected)
     */
    void onSliceComplete(AlgoOrder order, Slice slice);

    /**
     * Check if the order should be marked complete
     */
    boolean isOrderComplete(AlgoOrder order);

    /**
     * Get estimated time to completion (nanos)
     */
    long getEstimatedCompletionTime(AlgoOrder order, long currentTime);

    /**
     * Reset algorithm state for pool return
     */
    void reset();
}
