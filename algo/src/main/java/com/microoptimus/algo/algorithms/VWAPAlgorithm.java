package com.microoptimus.algo.algorithms;

import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;

import java.util.List;

/**
 * VWAPAlgorithm - Volume-Weighted Average Price execution
 *
 * Strategy:
 * - Uses historical volume profile to schedule slices
 * - Aims to achieve execution price close to market VWAP
 * - Adjusts participation based on real-time volume
 */
public class VWAPAlgorithm extends BaseAlgorithm {

    private long nextSliceId = 1;

    // Volume profile (simplified - would use real historical data)
    private double[] volumeProfile;
    private int currentBucket;
    private long bucketDuration;

    // Target quantities per bucket
    private long[] targetByBucket;
    private long[] executedByBucket;

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public void initialize(AlgoOrder order) {
        super.initialize(order);

        AlgoParameters params = order.getParameters();
        if (params == null) {
            params = AlgoParameters.vwap(100, 10000, 10, 0.10);
        }

        int numBuckets = params.getNumBuckets();
        this.volumeProfile = createDefaultVolumeProfile(numBuckets);
        this.targetByBucket = new long[numBuckets];
        this.executedByBucket = new long[numBuckets];
        this.currentBucket = 0;

        // Calculate duration per bucket
        long totalDuration = order.getEndTime() - order.getStartTime();
        this.bucketDuration = totalDuration / numBuckets;

        // Distribute quantity according to volume profile
        long totalQty = order.getTotalQuantity();
        long assigned = 0;
        for (int i = 0; i < numBuckets - 1; i++) {
            targetByBucket[i] = (long) (totalQty * volumeProfile[i]);
            assigned += targetByBucket[i];
        }
        targetByBucket[numBuckets - 1] = totalQty - assigned; // Remainder to last bucket
    }

    /**
     * Create default U-shaped volume profile (higher volume at open/close)
     */
    private double[] createDefaultVolumeProfile(int numBuckets) {
        double[] profile = new double[numBuckets];
        double total = 0;

        // U-shape: higher at beginning and end
        for (int i = 0; i < numBuckets; i++) {
            double position = (double) i / (numBuckets - 1); // 0 to 1
            // U-shape formula: higher at edges
            profile[i] = 1.0 + 0.5 * Math.pow(2 * position - 1, 2);
            total += profile[i];
        }

        // Normalize to sum to 1.0
        for (int i = 0; i < numBuckets; i++) {
            profile[i] /= total;
        }

        return profile;
    }

    @Override
    public boolean shouldGenerateSlice(AlgoOrder order, long currentTime) {
        if (currentTime < order.getStartTime() || currentTime > order.getEndTime()) {
            return false;
        }
        if (order.getLeavesQuantity() == 0) {
            return false;
        }

        // Update current bucket
        updateCurrentBucket(order, currentTime);

        // Check if we need to catch up in current bucket
        if (currentBucket < targetByBucket.length) {
            long bucketRemaining = targetByBucket[currentBucket] - executedByBucket[currentBucket];
            if (bucketRemaining > 0) {
                return intervalElapsed(currentTime, order.getParameters());
            }
        }

        return false;
    }

    private void updateCurrentBucket(AlgoOrder order, long currentTime) {
        long elapsed = currentTime - order.getStartTime();
        int newBucket = (int) (elapsed / bucketDuration);
        newBucket = Math.min(newBucket, targetByBucket.length - 1);
        this.currentBucket = newBucket;
    }

    @Override
    public List<Slice> generateSlices(AlgoOrder order, long currentTime, long currentPrice) {
        resultSlices.clear();

        if (!shouldGenerateSlice(order, currentTime)) {
            return resultSlices;
        }

        AlgoParameters params = order.getParameters();

        // Calculate how much we should have executed by now
        long targetByNow = 0;
        for (int i = 0; i <= currentBucket; i++) {
            targetByNow += targetByBucket[i];
        }

        // Calculate deficit
        long executed = order.getExecutedQuantity();
        long deficit = targetByNow - executed;

        // Determine slice size
        long sliceSize;
        if (deficit > 0) {
            // Behind schedule - larger slice
            sliceSize = Math.min(deficit, params.getMaxSliceSize());
        } else {
            // On or ahead of schedule - normal slice
            long bucketRemaining = targetByBucket[currentBucket] - executedByBucket[currentBucket];
            sliceSize = Math.min(bucketRemaining, params.getMaxSliceSize());
        }

        sliceSize = Math.max(sliceSize, params.getMinSliceSize());
        sliceSize = Math.min(sliceSize, order.getLeavesQuantity());

        if (sliceSize <= 0) {
            return resultSlices;
        }

        // Apply participation rate adjustment
        double participation = params.getParticipationRate();
        sliceSize = (long) (sliceSize * participation * 10); // Scale factor
        sliceSize = Math.min(sliceSize, params.getMaxSliceSize());
        sliceSize = Math.min(sliceSize, order.getLeavesQuantity());

        // Create slice
        Slice slice = acquireSlice();
        slice.init(nextSliceId++, order.getAlgoOrderId(), order.getSymbolIndex(),
                   order.getSide(), sliceSize, currentPrice,
                   slicesGeneratedCount + 1, currentTime);

        resultSlices.add(slice);
        lastSliceTime = currentTime;
        slicesGeneratedCount++;
        order.onSliceSent();

        return resultSlices;
    }

    @Override
    public void onSliceExecution(AlgoOrder order, Slice slice, long execQty, long execPrice) {
        super.onSliceExecution(order, slice, execQty, execPrice);

        // Track execution by bucket
        if (currentBucket < executedByBucket.length) {
            executedByBucket[currentBucket] += execQty;
        }
    }

    @Override
    public long getEstimatedCompletionTime(AlgoOrder order, long currentTime) {
        // Estimate based on remaining buckets
        if (order.getLeavesQuantity() == 0) {
            return currentTime;
        }

        int bucketsRemaining = targetByBucket.length - currentBucket;
        return currentTime + (bucketsRemaining * bucketDuration);
    }

    @Override
    public void reset() {
        super.reset();
        volumeProfile = null;
        targetByBucket = null;
        executedByBucket = null;
        currentBucket = 0;
        bucketDuration = 0;
    }
}
