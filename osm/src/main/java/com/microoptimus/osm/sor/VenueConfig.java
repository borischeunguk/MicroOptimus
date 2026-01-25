package com.microoptimus.osm.sor;

/**
 * VenueConfig - Configuration for a trading venue
 *
 * Includes all parameters needed for venue scoring and routing decisions.
 */
public class VenueConfig {

    private final VenueId venueId;
    private final int priority;              // Base priority (1-100)
    private final boolean enabled;           // Whether venue is active
    private final long maxOrderSize;         // Maximum single order size
    private final long avgLatencyNanos;      // Average execution latency
    private final double fillRate;           // Historical fill rate (0.0-1.0)
    private final double feesPerShare;       // Transaction fees per share

    // Dynamic state (updated at runtime)
    private volatile long lastLatencyNanos;
    private volatile double recentFillRate;
    private volatile boolean connected;

    public VenueConfig(VenueId venueId, int priority, boolean enabled,
                       long maxOrderSize, long avgLatencyNanos,
                       double fillRate, double feesPerShare) {
        this.venueId = venueId;
        this.priority = priority;
        this.enabled = enabled;
        this.maxOrderSize = maxOrderSize;
        this.avgLatencyNanos = avgLatencyNanos;
        this.fillRate = fillRate;
        this.feesPerShare = feesPerShare;
        this.lastLatencyNanos = avgLatencyNanos;
        this.recentFillRate = fillRate;
        this.connected = enabled;
    }

    // Getters
    public VenueId getVenueId() { return venueId; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public long getMaxOrderSize() { return maxOrderSize; }
    public long getAvgLatencyNanos() { return avgLatencyNanos; }
    public double getFillRate() { return fillRate; }
    public double getFeesPerShare() { return feesPerShare; }
    public long getLastLatencyNanos() { return lastLatencyNanos; }
    public double getRecentFillRate() { return recentFillRate; }
    public boolean isConnected() { return connected; }

    // State updates
    public void updateLatency(long latencyNanos) {
        this.lastLatencyNanos = latencyNanos;
    }

    public void updateFillRate(double rate) {
        this.recentFillRate = rate;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * Check if venue can handle the order
     */
    public boolean canHandle(long quantity) {
        return enabled && connected && quantity <= maxOrderSize;
    }

    @Override
    public String toString() {
        return String.format("VenueConfig[%s, priority=%d, enabled=%s, maxSize=%d, latency=%dns, fillRate=%.2f]",
                venueId, priority, enabled, maxOrderSize, avgLatencyNanos, fillRate);
    }
}
