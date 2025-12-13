package com.microoptimus.osm;

/**
 * LiquiditySource - Enum for tracking order sources with matching priority
 *
 * Priority order (lower number = higher priority):
 * 1. INTERNAL_TRADER - Highest priority (client orders)
 * 2. SIGNAL_MM - Medium priority (market maker/signal orders)
 * 3. EXTERNAL_EXCHANGE - Lowest priority (external venue orders)
 */
public enum LiquiditySource {
    INTERNAL_TRADER(1),      // Highest priority - internal client orders
    SIGNAL_MM(2),            // Medium priority - signal/market maker orders
    EXTERNAL_EXCHANGE(3);    // Lowest priority - external venue orders

    private final int priority;

    LiquiditySource(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * Compare priorities - lower number = higher priority
     */
    public boolean hasHigherPriorityThan(LiquiditySource other) {
        return this.priority < other.priority;
    }

    /**
     * Check if this source has equal priority to another
     */
    public boolean hasEqualPriorityTo(LiquiditySource other) {
        return this.priority == other.priority;
    }

    @Override
    public String toString() {
        return name() + "(priority=" + priority + ")";
    }
}
