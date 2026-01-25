package com.microoptimus.internaliser;

import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

/**
 * InternaliserStats - Latency and throughput metrics
 *
 * Uses HdrHistogram for accurate latency percentile tracking
 */
public class InternaliserStats {

    // Latency histogram (nanoseconds, max 10 seconds, 3 decimal precision)
    private final Histogram latencyHistogram;

    // Throughput counters
    private long orderCount;
    private long fillCount;
    private long partialFillCount;
    private long cancelCount;
    private long rejectCount;
    private long matchedQuantity;
    private long matchedValue;

    // Timing
    private long startTimeNanos;
    private long lastResetTimeNanos;

    public InternaliserStats() {
        this.latencyHistogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        this.startTimeNanos = System.nanoTime();
        this.lastResetTimeNanos = startTimeNanos;
    }

    /**
     * Record processing latency
     */
    public void recordLatency(long latencyNanos) {
        latencyHistogram.recordValue(Math.min(latencyNanos, latencyHistogram.getHighestTrackableValue()));
    }

    /**
     * Record order result
     */
    public void recordOrder(InternalMatchingEngine.MatchResult result) {
        orderCount++;
        switch (result.getStatus()) {
            case FILLED:
                fillCount++;
                break;
            case PARTIAL_FILL_RESTING:
            case PARTIAL_FILL_CANCELLED:
                partialFillCount++;
                break;
            case REJECTED:
                rejectCount++;
                break;
            default:
                break;
        }
    }

    /**
     * Record cancel result
     */
    public void recordCancel(InternalMatchingEngine.CancelResult result) {
        if (result.isSuccess()) {
            cancelCount++;
        }
    }

    /**
     * Reset statistics
     */
    public void reset() {
        latencyHistogram.reset();
        orderCount = 0;
        fillCount = 0;
        partialFillCount = 0;
        cancelCount = 0;
        rejectCount = 0;
        matchedQuantity = 0;
        matchedValue = 0;
        lastResetTimeNanos = System.nanoTime();
    }

    // Latency metrics (nanoseconds)
    public long getMinLatency() { return latencyHistogram.getMinValue(); }
    public long getMaxLatency() { return latencyHistogram.getMaxValue(); }
    public double getMeanLatency() { return latencyHistogram.getMean(); }
    public long getMedianLatency() { return latencyHistogram.getValueAtPercentile(50); }
    public long getP90Latency() { return latencyHistogram.getValueAtPercentile(90); }
    public long getP95Latency() { return latencyHistogram.getValueAtPercentile(95); }
    public long getP99Latency() { return latencyHistogram.getValueAtPercentile(99); }
    public long getP999Latency() { return latencyHistogram.getValueAtPercentile(99.9); }

    // Throughput metrics
    public long getOrderCount() { return orderCount; }
    public long getFillCount() { return fillCount; }
    public long getPartialFillCount() { return partialFillCount; }
    public long getCancelCount() { return cancelCount; }
    public long getRejectCount() { return rejectCount; }
    public long getMatchedQuantity() { return matchedQuantity; }
    public long getMatchedValue() { return matchedValue; }

    /**
     * Get orders per second since last reset
     */
    public double getOrdersPerSecond() {
        long elapsedNanos = System.nanoTime() - lastResetTimeNanos;
        if (elapsedNanos <= 0) return 0;
        return orderCount * 1_000_000_000.0 / elapsedNanos;
    }

    /**
     * Get fill rate (fills / orders)
     */
    public double getFillRate() {
        return orderCount > 0 ? (double)(fillCount + partialFillCount) / orderCount : 0;
    }

    /**
     * Get elapsed time since start
     */
    public long getElapsedTimeNanos() {
        return System.nanoTime() - startTimeNanos;
    }

    /**
     * Get elapsed time since last reset
     */
    public long getTimeSinceResetNanos() {
        return System.nanoTime() - lastResetTimeNanos;
    }

    @Override
    public String toString() {
        return String.format(
            "InternaliserStats[orders=%d, fills=%d, partials=%d, cancels=%d, rejects=%d, " +
            "rate=%.0f/s, latency(ns): min=%d, mean=%.0f, p50=%d, p99=%d, p99.9=%d, max=%d]",
            orderCount, fillCount, partialFillCount, cancelCount, rejectCount,
            getOrdersPerSecond(),
            getMinLatency(), getMeanLatency(), getMedianLatency(),
            getP99Latency(), getP999Latency(), getMaxLatency()
        );
    }

    /**
     * Get detailed latency report
     */
    public String getLatencyReport() {
        return String.format(
            "Latency Report (nanoseconds):\n" +
            "  Min:    %,d\n" +
            "  Mean:   %,.0f\n" +
            "  P50:    %,d\n" +
            "  P90:    %,d\n" +
            "  P95:    %,d\n" +
            "  P99:    %,d\n" +
            "  P99.9:  %,d\n" +
            "  Max:    %,d\n" +
            "  Count:  %,d",
            getMinLatency(), getMeanLatency(), getMedianLatency(),
            getP90Latency(), getP95Latency(), getP99Latency(),
            getP999Latency(), getMaxLatency(),
            latencyHistogram.getTotalCount()
        );
    }
}
