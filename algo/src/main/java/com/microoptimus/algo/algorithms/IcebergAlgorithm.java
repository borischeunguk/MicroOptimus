package com.microoptimus.algo.algorithms;

import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;

import java.util.List;

/**
 * IcebergAlgorithm - Hidden liquidity execution
 *
 * Strategy:
 * - Display only a portion of the total order (display quantity)
 * - When displayed portion fills, refresh with new slice
 * - Hides true order size from market
 */
public class IcebergAlgorithm extends BaseAlgorithm {

    private long nextSliceId = 1;
    private Slice activeSlice;
    private long displayQuantity;
    private long refreshThreshold;

    @Override
    public String getName() {
        return "ICEBERG";
    }

    @Override
    public void initialize(AlgoOrder order) {
        super.initialize(order);

        AlgoParameters params = order.getParameters();
        if (params == null) {
            params = AlgoParameters.iceberg(100, 0);
        }

        this.displayQuantity = params.getDisplayQuantity();
        this.refreshThreshold = params.getRefreshThreshold();
        this.activeSlice = null;
    }

    @Override
    public boolean shouldGenerateSlice(AlgoOrder order, long currentTime) {
        if (currentTime < order.getStartTime() || currentTime > order.getEndTime()) {
            return false;
        }
        if (order.getLeavesQuantity() == 0) {
            return false;
        }

        // Generate slice if no active slice
        if (activeSlice == null) {
            return true;
        }

        // Generate new slice if active slice is terminal
        if (activeSlice.isTerminal()) {
            return true;
        }

        // Generate new slice if leaves below threshold
        if (activeSlice.getLeavesQuantity() <= refreshThreshold) {
            return true;
        }

        return false;
    }

    @Override
    public List<Slice> generateSlices(AlgoOrder order, long currentTime, long currentPrice) {
        resultSlices.clear();

        if (!shouldGenerateSlice(order, currentTime)) {
            return resultSlices;
        }

        // Calculate slice size
        long remaining = order.getLeavesQuantity();
        long sliceSize = Math.min(displayQuantity, remaining);

        if (sliceSize <= 0) {
            return resultSlices;
        }

        // Create new iceberg slice
        Slice slice = acquireSlice();
        slice.init(nextSliceId++, order.getAlgoOrderId(), order.getSymbolIndex(),
                   order.getSide(), sliceSize, currentPrice,
                   slicesGeneratedCount + 1, currentTime);

        activeSlice = slice;
        resultSlices.add(slice);
        lastSliceTime = currentTime;
        slicesGeneratedCount++;
        order.onSliceSent();

        return resultSlices;
    }

    @Override
    public void onSliceExecution(AlgoOrder order, Slice slice, long execQty, long execPrice) {
        super.onSliceExecution(order, slice, execQty, execPrice);

        // Check if we need to refresh the iceberg
        if (slice == activeSlice && slice.getLeavesQuantity() <= refreshThreshold) {
            // Will generate new slice on next shouldGenerateSlice call
        }
    }

    @Override
    public void onSliceComplete(AlgoOrder order, Slice slice) {
        super.onSliceComplete(order, slice);

        // Clear active slice when complete
        if (slice == activeSlice) {
            activeSlice = null;
        }
    }

    @Override
    public long getEstimatedCompletionTime(AlgoOrder order, long currentTime) {
        // Iceberg has no predictable completion time
        // Depends on market conditions
        return order.getEndTime();
    }

    @Override
    public void reset() {
        super.reset();
        activeSlice = null;
        displayQuantity = 0;
        refreshThreshold = 0;
    }
}
