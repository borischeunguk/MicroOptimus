package com.microoptimus.signal.strategy;

import com.microoptimus.common.types.Side;
import com.microoptimus.signal.principal.PrincipalTradingBook;
import org.agrona.collections.Int2ObjectHashMap;

/**
 * InventoryManager - Manages inventory skew for market making
 *
 * Responsibilities:
 * - Track target vs actual inventory
 * - Calculate skew adjustments for quotes
 * - Manage inventory rebalancing
 */
public class InventoryManager {

    private final PrincipalTradingBook tradingBook;

    // Target positions by symbol
    private final Int2ObjectHashMap<InventoryTarget> targets;

    // Global parameters
    private double maxSkew = 1.0;          // Max skew factor
    private double skewSensitivity = 0.5;  // How quickly skew increases with position
    private long halfLife = 60_000_000_000L; // 60 seconds in nanos for position decay target

    public InventoryManager(PrincipalTradingBook tradingBook) {
        this.tradingBook = tradingBook;
        this.targets = new Int2ObjectHashMap<>();
    }

    /**
     * Set target position for symbol
     */
    public void setTarget(int symbolIndex, long targetPosition, long maxPosition) {
        InventoryTarget target = targets.get(symbolIndex);
        if (target == null) {
            target = new InventoryTarget(symbolIndex);
            targets.put(symbolIndex, target);
        }
        target.targetPosition = targetPosition;
        target.maxPosition = maxPosition;
    }

    /**
     * Get inventory skew for quote pricing
     * Returns value between -maxSkew and +maxSkew
     */
    public double getSkew(int symbolIndex) {
        InventoryTarget target = targets.get(symbolIndex);
        long currentPosition = tradingBook.getNetPosition(symbolIndex);

        if (target == null) {
            // No target - use position ratio directly
            return calculateSkew(currentPosition, 10_000); // Default max
        }

        // Calculate deviation from target
        long deviation = currentPosition - target.targetPosition;

        return calculateSkew(deviation, target.maxPosition);
    }

    /**
     * Calculate skew from deviation and max
     */
    private double calculateSkew(long deviation, long maxPosition) {
        if (maxPosition == 0) {
            return 0;
        }

        double ratio = (double) deviation / maxPosition;

        // Apply sigmoid-like function for smooth skew
        double skew = ratio * skewSensitivity;

        // Clamp to max skew
        return Math.max(-maxSkew, Math.min(maxSkew, skew));
    }

    /**
     * Get recommended quote size adjustment
     * Reduces size on side that would increase position deviation
     */
    public double getSizeAdjustment(int symbolIndex, Side side) {
        double skew = getSkew(symbolIndex);

        // If skew is positive (long position), reduce buy size
        // If skew is negative (short position), reduce sell size
        if (side == Side.BUY && skew > 0) {
            return 1.0 - Math.abs(skew) * 0.5; // Up to 50% reduction
        } else if (side == Side.SELL && skew < 0) {
            return 1.0 - Math.abs(skew) * 0.5;
        }

        return 1.0; // No adjustment
    }

    /**
     * Get urgency factor for hedging
     * Higher urgency when position is far from target
     */
    public double getHedgeUrgency(int symbolIndex) {
        double skew = Math.abs(getSkew(symbolIndex));

        // Exponential urgency as skew increases
        return Math.pow(1.0 + skew, 2);
    }

    /**
     * Check if position needs active hedging
     */
    public boolean needsHedging(int symbolIndex) {
        double skew = Math.abs(getSkew(symbolIndex));
        return skew > 0.5; // More than 50% of max position
    }

    /**
     * Get recommended hedge quantity
     */
    public long getHedgeQuantity(int symbolIndex, long baseQuantity) {
        InventoryTarget target = targets.get(symbolIndex);
        long currentPosition = tradingBook.getNetPosition(symbolIndex);
        long targetPos = target != null ? target.targetPosition : 0;

        long deviation = currentPosition - targetPos;
        long hedgeQty = Math.abs(deviation);

        // Cap at multiple of base quantity
        return Math.min(hedgeQty, baseQuantity * 10);
    }

    /**
     * Get side for hedging (opposite of current position)
     */
    public Side getHedgeSide(int symbolIndex) {
        long position = tradingBook.getNetPosition(symbolIndex);
        return position > 0 ? Side.SELL : Side.BUY;
    }

    /**
     * Update target based on time decay
     */
    public void decayTargets(long currentTime) {
        targets.forEach((symbolIndex, target) -> {
            // Decay target position towards zero over time
            if (target.targetPosition != 0 && target.lastDecayTime > 0) {
                long elapsed = currentTime - target.lastDecayTime;
                double decayFactor = Math.exp(-elapsed / (double) halfLife);
                target.targetPosition = (long) (target.targetPosition * decayFactor);
            }
            target.lastDecayTime = currentTime;
        });
    }

    // Configuration
    public void setMaxSkew(double max) { this.maxSkew = max; }
    public void setSkewSensitivity(double sensitivity) { this.skewSensitivity = sensitivity; }
    public void setHalfLife(long halfLifeNanos) { this.halfLife = halfLifeNanos; }

    /**
     * Inventory target per symbol
     */
    public static class InventoryTarget {
        public final int symbolIndex;
        public long targetPosition;
        public long maxPosition;
        public long lastDecayTime;

        public InventoryTarget(int symbolIndex) {
            this.symbolIndex = symbolIndex;
            this.maxPosition = 10_000;
        }
    }
}
