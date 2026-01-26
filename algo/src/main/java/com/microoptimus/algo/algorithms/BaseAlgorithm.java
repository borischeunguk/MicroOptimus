package com.microoptimus.algo.algorithms;

import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;

import java.util.ArrayList;
import java.util.List;

/**
 * BaseAlgorithm - Abstract base class with common algorithm functionality
 */
public abstract class BaseAlgorithm implements Algorithm {

    // Slice pool (pre-allocated for GC-free operation)
    protected final Slice[] slicePool;
    protected int slicePoolIndex;
    protected static final int SLICE_POOL_SIZE = 256;

    // Working state
    protected long lastSliceTime;
    protected int slicesGeneratedCount;

    // Reusable list for returning slices (avoid allocation)
    protected final List<Slice> resultSlices;

    protected BaseAlgorithm() {
        this.slicePool = new Slice[SLICE_POOL_SIZE];
        for (int i = 0; i < SLICE_POOL_SIZE; i++) {
            slicePool[i] = new Slice();
        }
        this.slicePoolIndex = 0;
        this.resultSlices = new ArrayList<>(4);
    }

    @Override
    public void initialize(AlgoOrder order) {
        this.lastSliceTime = 0;
        this.slicesGeneratedCount = 0;
    }

    @Override
    public void onSliceExecution(AlgoOrder order, Slice slice, long execQty, long execPrice) {
        // Default: update slice and order
        slice.onExecution(execQty, execPrice, System.nanoTime());
        order.onSliceFill(execQty, execPrice, System.nanoTime());
    }

    @Override
    public void onSliceComplete(AlgoOrder order, Slice slice) {
        if (slice.isFilled()) {
            order.onSliceFilled();
        } else {
            order.onSliceCancelled();
        }
    }

    @Override
    public boolean isOrderComplete(AlgoOrder order) {
        return order.getLeavesQuantity() == 0 || order.isTerminal();
    }

    @Override
    public void reset() {
        lastSliceTime = 0;
        slicesGeneratedCount = 0;
        resultSlices.clear();
        slicePoolIndex = 0;
    }

    /**
     * Acquire a slice from the pool
     */
    protected Slice acquireSlice() {
        Slice slice = slicePool[slicePoolIndex];
        slicePoolIndex = (slicePoolIndex + 1) % SLICE_POOL_SIZE;
        return slice;
    }

    /**
     * Calculate slice size based on parameters and remaining quantity
     */
    protected long calculateSliceSize(AlgoOrder order, AlgoParameters params) {
        long remaining = order.getLeavesQuantity();
        long sliceSize = Math.min(remaining, params.getMaxSliceSize());
        sliceSize = Math.max(sliceSize, params.getMinSliceSize());
        return Math.min(sliceSize, remaining);
    }

    /**
     * Check if enough time has passed since last slice
     */
    protected boolean intervalElapsed(long currentTime, AlgoParameters params) {
        if (lastSliceTime == 0) {
            return true;
        }
        long intervalNanos = params.getSliceIntervalMs() * 1_000_000L;
        return (currentTime - lastSliceTime) >= intervalNanos;
    }

    /**
     * Apply urgency factor to slice size
     */
    protected long applyUrgency(long baseSize, AlgoParameters params) {
        return (long) (baseSize * params.getUrgencyFactor());
    }
}
