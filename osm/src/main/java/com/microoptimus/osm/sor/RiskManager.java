package com.microoptimus.osm.sor;

import com.microoptimus.common.types.OrderType;
import org.agrona.collections.Long2LongHashMap;

/**
 * RiskManager - Pre-trade risk checks for order routing
 *
 * Checks include:
 * - Order size limits
 * - Position limits
 * - Price reasonability
 * - Rate limits
 * - Client limits
 */
public class RiskManager {

    // Order limits
    private long maxOrderSize = 1_000_000;
    private long maxOrderValue = 10_000_000_000L; // $10M at price scale
    private long minOrderSize = 1;

    // Price limits
    private double maxPriceDeviationPercent = 10.0; // Max 10% from reference
    private long referencePrice = 0;

    // Rate limits
    private int maxOrdersPerSecond = 10_000;
    private int maxOrdersPerClient = 1_000;

    // Position tracking (simplified - would use proper position service)
    private final Long2LongHashMap clientPositions;
    private long maxPosition = 100_000_000L;

    // Order rate tracking
    private long lastSecondStart;
    private int ordersThisSecond;
    private final Long2LongHashMap clientOrderCounts;

    public RiskManager() {
        this.clientPositions = new Long2LongHashMap(0);
        this.clientOrderCounts = new Long2LongHashMap(0);
        this.lastSecondStart = System.currentTimeMillis() / 1000;
    }

    /**
     * Check order against risk limits
     */
    public RiskCheckResult checkOrder(OrderRequest request) {
        // Order size check
        if (request.quantity < minOrderSize) {
            return RiskCheckResult.reject("Order size below minimum: " + request.quantity);
        }

        if (request.quantity > maxOrderSize) {
            return RiskCheckResult.reject("Order size exceeds maximum: " + request.quantity);
        }

        // Order value check
        if (request.orderType == OrderType.LIMIT) {
            long orderValue = request.price * request.quantity;
            if (orderValue > maxOrderValue) {
                return RiskCheckResult.reject("Order value exceeds maximum: " + orderValue);
            }
        }

        // Price reasonability check (for limit orders)
        if (request.orderType == OrderType.LIMIT && referencePrice > 0) {
            double deviation = Math.abs(request.price - referencePrice) * 100.0 / referencePrice;
            if (deviation > maxPriceDeviationPercent) {
                return RiskCheckResult.reject("Price deviation exceeds limit: " + deviation + "%");
            }
        }

        // Rate limit check
        long currentSecond = System.currentTimeMillis() / 1000;
        if (currentSecond != lastSecondStart) {
            lastSecondStart = currentSecond;
            ordersThisSecond = 0;
            clientOrderCounts.clear();
        }

        ordersThisSecond++;
        if (ordersThisSecond > maxOrdersPerSecond) {
            return RiskCheckResult.reject("Global rate limit exceeded");
        }

        long clientOrders = clientOrderCounts.get(request.clientId) + 1;
        clientOrderCounts.put(request.clientId, clientOrders);
        if (clientOrders > maxOrdersPerClient) {
            return RiskCheckResult.reject("Client rate limit exceeded");
        }

        // Position check (simplified)
        long currentPosition = clientPositions.get(request.clientId);
        long projectedPosition = currentPosition +
                (request.side == com.microoptimus.common.types.Side.BUY ?
                        request.quantity : -request.quantity);
        if (Math.abs(projectedPosition) > maxPosition) {
            return RiskCheckResult.reject("Position limit exceeded");
        }

        return RiskCheckResult.approve();
    }

    /**
     * Update position after fill
     */
    public void updatePosition(long clientId, com.microoptimus.common.types.Side side, long quantity) {
        long current = clientPositions.get(clientId);
        long delta = (side == com.microoptimus.common.types.Side.BUY) ? quantity : -quantity;
        clientPositions.put(clientId, current + delta);
    }

    /**
     * Set reference price for price checks
     */
    public void setReferencePrice(long price) {
        this.referencePrice = price;
    }

    // Configuration setters
    public void setMaxOrderSize(long max) { this.maxOrderSize = max; }
    public void setMaxOrderValue(long max) { this.maxOrderValue = max; }
    public void setMinOrderSize(long min) { this.minOrderSize = min; }
    public void setMaxPriceDeviationPercent(double max) { this.maxPriceDeviationPercent = max; }
    public void setMaxOrdersPerSecond(int max) { this.maxOrdersPerSecond = max; }
    public void setMaxOrdersPerClient(int max) { this.maxOrdersPerClient = max; }
    public void setMaxPosition(long max) { this.maxPosition = max; }

    /**
     * Risk check result
     */
    public static class RiskCheckResult {
        private final boolean approved;
        private final String rejectReason;

        private RiskCheckResult(boolean approved, String reason) {
            this.approved = approved;
            this.rejectReason = reason;
        }

        public static RiskCheckResult approve() {
            return new RiskCheckResult(true, null);
        }

        public static RiskCheckResult reject(String reason) {
            return new RiskCheckResult(false, reason);
        }

        public boolean isApproved() { return approved; }
        public String getRejectReason() { return rejectReason; }
    }
}
