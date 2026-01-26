package com.microoptimus.algo.algorithms;

import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;

import java.util.Collections;
import java.util.List;

/**
 * TWAPAlgorithm - Time-Weighted Average Price execution
 *
 * Strategy:
 * - Divide order into equal-sized slices over time
 * - Execute one slice per interval
 * - Aims to achieve average execution price close to TWAP
 */
public class TWAPAlgorithm extends BaseAlgorithm {

    private long nextSliceId = 1;
    private long intervalNanos;
    private int totalSlices;
    private long sliceSize;

    @Override
    public String getName() {
        return "TWAP";
    }

    @Override
    public void initialize(AlgoOrder order) {
        super.initialize(order);

        AlgoParameters params = order.getParameters();
        if (params == null) {
            params = AlgoParameters.twap(100, 10000, 1000);
        }

        // Calculate execution timeline
        long durationNanos = order.getEndTime() - order.getStartTime();
        long intervalMs = params.getSliceIntervalMs();
        this.intervalNanos = intervalMs * 1_000_000L;

        // Calculate number of slices
        this.totalSlices = Math.max(1, (int)(durationNanos / intervalNanos));

        // Calculate base slice size
        this.sliceSize = order.getTotalQuantity() / totalSlices;
        this.sliceSize = Math.max(sliceSize, params.getMinSliceSize());
        this.sliceSize = Math.min(sliceSize, params.getMaxSliceSize());
    }

    @Override
    public boolean shouldGenerateSlice(AlgoOrder order, long currentTime) {
        // Check if we're in the execution window
        if (currentTime < order.getStartTime()) {
            return false;
        }
        if (currentTime > order.getEndTime()) {
            return false;
        }

        // Check if we have remaining quantity
        if (order.getLeavesQuantity() == 0) {
            return false;
        }

        // Check if interval has elapsed
        return intervalElapsed(currentTime, order.getParameters());
    }

    @Override
    public List<Slice> generateSlices(AlgoOrder order, long currentTime, long currentPrice) {
        resultSlices.clear();

        if (!shouldGenerateSlice(order, currentTime)) {
            return resultSlices;
        }

        // Calculate slice size for this interval
        long remaining = order.getLeavesQuantity();
        long thisSliceSize = Math.min(sliceSize, remaining);

        // For last slice, take all remaining
        int slicesRemaining = totalSlices - slicesGeneratedCount;
        if (slicesRemaining <= 1) {
            thisSliceSize = remaining;
        }

        // Apply urgency factor if configured
        AlgoParameters params = order.getParameters();
        if (params != null) {
            thisSliceSize = applyUrgency(thisSliceSize, params);
            thisSliceSize = Math.min(thisSliceSize, remaining);
        }

        // Create slice
        Slice slice = acquireSlice();
        slice.init(nextSliceId++, order.getAlgoOrderId(), order.getSymbolIndex(),
                   order.getSide(), thisSliceSize, currentPrice,
                   slicesGeneratedCount + 1, currentTime);

        resultSlices.add(slice);
        lastSliceTime = currentTime;
        slicesGeneratedCount++;
        order.onSliceSent();

        return resultSlices;
    }

    @Override
    public long getEstimatedCompletionTime(AlgoOrder order, long currentTime) {
        if (order.getLeavesQuantity() == 0) {
            return currentTime;
        }

        int slicesRemaining = totalSlices - slicesGeneratedCount;
        return currentTime + (slicesRemaining * intervalNanos);
    }

    @Override
    public void reset() {
        super.reset();
        intervalNanos = 0;
        totalSlices = 0;
        sliceSize = 0;
    }
}
