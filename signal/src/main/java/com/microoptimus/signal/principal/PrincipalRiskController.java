package com.microoptimus.signal.principal;

import com.microoptimus.common.types.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PrincipalRiskController - Risk management for principal trading
 *
 * Enforces limits on:
 * - Position size
 * - P&L (daily loss limit)
 * - Notional exposure
 * - Quote rates
 */
public class PrincipalRiskController {

    private static final Logger log = LoggerFactory.getLogger(PrincipalRiskController.class);

    private final PrincipalTradingBook tradingBook;

    // Position limits
    private long maxPositionPerSymbol = 100_000;
    private long maxGrossExposure = 10_000_000_000L; // $10M at price scale
    private long maxNetExposure = 1_000_000_000L;    // $1M net

    // P&L limits
    private long maxDailyLoss = 100_000_000L;       // $100K loss limit
    private long unrealizedLossWarning = 50_000_000L;

    // Order limits
    private long maxOrderSize = 10_000;
    private long maxOrderNotional = 100_000_000L;   // $100K per order

    // Rate limits
    private int maxQuotesPerSecond = 100;
    private int quotesThisSecond;
    private long lastSecondStart;

    // State
    private volatile boolean tradingEnabled = true;
    private String disableReason;

    public PrincipalRiskController(PrincipalTradingBook tradingBook) {
        this.tradingBook = tradingBook;
        this.lastSecondStart = System.currentTimeMillis() / 1000;
    }

    /**
     * Check if a quote/order is allowed
     */
    public RiskCheckResult checkQuote(int symbolIndex, Side side, long price, long quantity) {
        // Trading enabled check
        if (!tradingEnabled) {
            return RiskCheckResult.reject("Trading disabled: " + disableReason);
        }

        // Order size check
        if (quantity > maxOrderSize) {
            return RiskCheckResult.reject("Order size exceeds limit: " + quantity);
        }

        // Order notional check
        long notional = price * quantity;
        if (notional > maxOrderNotional) {
            return RiskCheckResult.reject("Order notional exceeds limit: " + notional);
        }

        // Position limit check
        if (!tradingBook.isWithinLimits(symbolIndex, side, quantity)) {
            return RiskCheckResult.reject("Position limit would be exceeded");
        }

        // Exposure check
        if (tradingBook.getGrossExposure() + notional > maxGrossExposure) {
            return RiskCheckResult.reject("Gross exposure limit would be exceeded");
        }

        // P&L check
        long totalPnL = tradingBook.getTotalPnL();
        if (totalPnL < -maxDailyLoss) {
            disableTrading("Daily loss limit breached: " + totalPnL);
            return RiskCheckResult.reject("Daily loss limit breached");
        }

        // Rate limit check
        if (!checkRateLimit()) {
            return RiskCheckResult.reject("Quote rate limit exceeded");
        }

        // Unrealized loss warning
        if (tradingBook.getUnrealizedPnL() < -unrealizedLossWarning) {
            log.warn("Unrealized loss warning threshold reached: {}",
                    tradingBook.getUnrealizedPnL());
        }

        return RiskCheckResult.approve();
    }

    /**
     * Check quote rate limit
     */
    private boolean checkRateLimit() {
        long currentSecond = System.currentTimeMillis() / 1000;
        if (currentSecond != lastSecondStart) {
            lastSecondStart = currentSecond;
            quotesThisSecond = 0;
        }

        quotesThisSecond++;
        return quotesThisSecond <= maxQuotesPerSecond;
    }

    /**
     * Disable trading with reason
     */
    public void disableTrading(String reason) {
        this.tradingEnabled = false;
        this.disableReason = reason;
        log.warn("Trading DISABLED: {}", reason);
    }

    /**
     * Re-enable trading
     */
    public void enableTrading() {
        this.tradingEnabled = true;
        this.disableReason = null;
        log.info("Trading ENABLED");
    }

    /**
     * Get inventory skew suggestion based on position
     */
    public double getInventorySkew(int symbolIndex) {
        long position = tradingBook.getNetPosition(symbolIndex);
        if (position == 0) {
            return 0;
        }

        // Skew quotes to reduce position
        // Positive position -> skew to sell (negative skew)
        // Negative position -> skew to buy (positive skew)
        double ratio = (double) position / maxPositionPerSymbol;
        return -ratio; // -1 to +1
    }

    /**
     * Calculate position-adjusted spread
     */
    public long getAdjustedSpread(int symbolIndex, long baseSpread) {
        double skew = Math.abs(getInventorySkew(symbolIndex));
        // Widen spread when position is large
        double spreadMultiplier = 1.0 + skew * 0.5; // Up to 50% wider
        return (long) (baseSpread * spreadMultiplier);
    }

    // Configuration setters
    public void setMaxPositionPerSymbol(long max) { this.maxPositionPerSymbol = max; }
    public void setMaxGrossExposure(long max) { this.maxGrossExposure = max; }
    public void setMaxNetExposure(long max) { this.maxNetExposure = max; }
    public void setMaxDailyLoss(long max) { this.maxDailyLoss = max; }
    public void setMaxOrderSize(long max) { this.maxOrderSize = max; }
    public void setMaxOrderNotional(long max) { this.maxOrderNotional = max; }
    public void setMaxQuotesPerSecond(int max) { this.maxQuotesPerSecond = max; }

    // Getters
    public boolean isTradingEnabled() { return tradingEnabled; }
    public String getDisableReason() { return disableReason; }

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
