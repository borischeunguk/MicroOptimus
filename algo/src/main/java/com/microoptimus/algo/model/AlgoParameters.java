package com.microoptimus.algo.model;

/**
 * AlgoParameters - Algorithm-specific parameters
 *
 * Contains parameters for different algorithm types.
 */
public class AlgoParameters {

    // Common parameters
    private long minSliceSize = 100;
    private long maxSliceSize = 10000;
    private int sliceIntervalMs = 1000;  // Default 1 second between slices
    private double urgencyFactor = 1.0;  // 1.0 = normal, >1 = more aggressive

    // VWAP/TWAP specific
    private int numBuckets = 10;         // Time buckets for scheduling
    private double participationRate = 0.10;  // Target % of volume

    // Iceberg specific
    private long displayQuantity = 100;  // Visible quantity
    private long refreshThreshold = 0;   // Quantity threshold to refresh (0 = immediate)

    // POV (Percentage of Volume) specific
    private double targetPov = 0.05;     // Target 5% of volume
    private int povLookbackMs = 60000;   // Lookback window for volume calc

    // Price limits
    private long maxPrice = Long.MAX_VALUE;
    private long minPrice = 0;
    private double maxSlippage = 0.01;   // Max 1% slippage

    // Risk parameters
    private long maxNotional = Long.MAX_VALUE;
    private int maxSlicesPerMinute = 60;

    public AlgoParameters() {}

    /**
     * Create VWAP parameters
     */
    public static AlgoParameters vwap(long minSlice, long maxSlice, int buckets, double participation) {
        AlgoParameters params = new AlgoParameters();
        params.minSliceSize = minSlice;
        params.maxSliceSize = maxSlice;
        params.numBuckets = buckets;
        params.participationRate = participation;
        return params;
    }

    /**
     * Create TWAP parameters
     */
    public static AlgoParameters twap(long minSlice, long maxSlice, int intervalMs) {
        AlgoParameters params = new AlgoParameters();
        params.minSliceSize = minSlice;
        params.maxSliceSize = maxSlice;
        params.sliceIntervalMs = intervalMs;
        return params;
    }

    /**
     * Create Iceberg parameters
     */
    public static AlgoParameters iceberg(long displayQty, long refreshThreshold) {
        AlgoParameters params = new AlgoParameters();
        params.displayQuantity = displayQty;
        params.refreshThreshold = refreshThreshold;
        params.minSliceSize = displayQty;
        params.maxSliceSize = displayQty;
        return params;
    }

    /**
     * Create POV parameters
     */
    public static AlgoParameters pov(double targetPov, int lookbackMs) {
        AlgoParameters params = new AlgoParameters();
        params.targetPov = targetPov;
        params.povLookbackMs = lookbackMs;
        return params;
    }

    // Getters and setters
    public long getMinSliceSize() { return minSliceSize; }
    public void setMinSliceSize(long minSliceSize) { this.minSliceSize = minSliceSize; }

    public long getMaxSliceSize() { return maxSliceSize; }
    public void setMaxSliceSize(long maxSliceSize) { this.maxSliceSize = maxSliceSize; }

    public int getSliceIntervalMs() { return sliceIntervalMs; }
    public void setSliceIntervalMs(int sliceIntervalMs) { this.sliceIntervalMs = sliceIntervalMs; }

    public double getUrgencyFactor() { return urgencyFactor; }
    public void setUrgencyFactor(double urgencyFactor) { this.urgencyFactor = urgencyFactor; }

    public int getNumBuckets() { return numBuckets; }
    public void setNumBuckets(int numBuckets) { this.numBuckets = numBuckets; }

    public double getParticipationRate() { return participationRate; }
    public void setParticipationRate(double participationRate) { this.participationRate = participationRate; }

    public long getDisplayQuantity() { return displayQuantity; }
    public void setDisplayQuantity(long displayQuantity) { this.displayQuantity = displayQuantity; }

    public long getRefreshThreshold() { return refreshThreshold; }
    public void setRefreshThreshold(long refreshThreshold) { this.refreshThreshold = refreshThreshold; }

    public double getTargetPov() { return targetPov; }
    public void setTargetPov(double targetPov) { this.targetPov = targetPov; }

    public int getPovLookbackMs() { return povLookbackMs; }
    public void setPovLookbackMs(int povLookbackMs) { this.povLookbackMs = povLookbackMs; }

    public long getMaxPrice() { return maxPrice; }
    public void setMaxPrice(long maxPrice) { this.maxPrice = maxPrice; }

    public long getMinPrice() { return minPrice; }
    public void setMinPrice(long minPrice) { this.minPrice = minPrice; }

    public double getMaxSlippage() { return maxSlippage; }
    public void setMaxSlippage(double maxSlippage) { this.maxSlippage = maxSlippage; }

    public long getMaxNotional() { return maxNotional; }
    public void setMaxNotional(long maxNotional) { this.maxNotional = maxNotional; }

    public int getMaxSlicesPerMinute() { return maxSlicesPerMinute; }
    public void setMaxSlicesPerMinute(int maxSlicesPerMinute) { this.maxSlicesPerMinute = maxSlicesPerMinute; }

    @Override
    public String toString() {
        return String.format("AlgoParameters[slice=%d-%d, interval=%dms, urgency=%.1f]",
                minSliceSize, maxSliceSize, sliceIntervalMs, urgencyFactor);
    }
}
