package com.microoptimus.signal.principal;

import com.microoptimus.common.types.Side;
import org.agrona.collections.Int2ObjectHashMap;

/**
 * PrincipalTradingBook - Manages principal/proprietary positions
 *
 * Tracks positions, P&L, and exposure for market-making activity.
 */
public class PrincipalTradingBook {

    // Position by symbol (keyed by symbol index)
    private final Int2ObjectHashMap<Position> positions;

    // Global P&L tracking
    private long realizedPnL;
    private long unrealizedPnL;
    private long totalNotional;
    private long maxNotional;

    // Risk metrics
    private long maxPosition;
    private long grossExposure;
    private long netExposure;

    public PrincipalTradingBook() {
        this.positions = new Int2ObjectHashMap<>();
        this.maxNotional = Long.MAX_VALUE;
        this.maxPosition = Long.MAX_VALUE;
    }

    /**
     * Record a trade execution
     */
    public void onTrade(int symbolIndex, Side side, long quantity, long price, long timestamp) {
        Position position = getOrCreatePosition(symbolIndex);

        // Update position
        long signedQty = (side == Side.BUY) ? quantity : -quantity;
        long previousPosition = position.netPosition;
        position.netPosition += signedQty;

        // Calculate realized P&L on position reduction
        if (Math.abs(position.netPosition) < Math.abs(previousPosition)) {
            // Position was reduced - realize P&L
            long closedQty = Math.min(Math.abs(signedQty), Math.abs(previousPosition));
            long pnl = (price - position.avgEntryPrice) * closedQty;
            if (previousPosition < 0) {
                pnl = -pnl; // Short position: profit when price goes down
            }
            realizedPnL += pnl;
            position.realizedPnL += pnl;
        }

        // Update average entry price on position increase
        if (Math.abs(position.netPosition) > Math.abs(previousPosition)) {
            // Position increased - update average entry
            long totalCost = position.avgEntryPrice * Math.abs(previousPosition) + price * quantity;
            position.avgEntryPrice = totalCost / Math.abs(position.netPosition);
        }

        // Update notional
        long tradeNotional = price * quantity;
        position.totalNotional += tradeNotional;
        totalNotional += tradeNotional;

        // Update volume stats
        if (side == Side.BUY) {
            position.buyQuantity += quantity;
            position.buyNotional += tradeNotional;
        } else {
            position.sellQuantity += quantity;
            position.sellNotional += tradeNotional;
        }

        position.lastUpdateTimestamp = timestamp;
        position.tradeCount++;

        updateExposures();
    }

    /**
     * Update unrealized P&L based on current market price
     */
    public void updateMarkToMarket(int symbolIndex, long bidPrice, long askPrice) {
        Position position = positions.get(symbolIndex);
        if (position == null || position.netPosition == 0) {
            return;
        }

        // Mark at mid price
        long markPrice = (bidPrice + askPrice) / 2;
        position.lastMarkPrice = markPrice;

        // Calculate unrealized P&L
        long unrealized = (markPrice - position.avgEntryPrice) * position.netPosition;
        unrealizedPnL -= position.unrealizedPnL; // Remove old
        position.unrealizedPnL = unrealized;
        unrealizedPnL += unrealized; // Add new
    }

    /**
     * Get position for symbol
     */
    public Position getPosition(int symbolIndex) {
        return positions.get(symbolIndex);
    }

    /**
     * Get net position for symbol
     */
    public long getNetPosition(int symbolIndex) {
        Position position = positions.get(symbolIndex);
        return position != null ? position.netPosition : 0;
    }

    /**
     * Check if position is within limits
     */
    public boolean isWithinLimits(int symbolIndex, Side side, long quantity) {
        Position position = positions.get(symbolIndex);
        long currentPosition = position != null ? position.netPosition : 0;

        long signedQty = (side == Side.BUY) ? quantity : -quantity;
        long projectedPosition = currentPosition + signedQty;

        return Math.abs(projectedPosition) <= maxPosition;
    }

    /**
     * Get or create position for symbol
     */
    private Position getOrCreatePosition(int symbolIndex) {
        Position position = positions.get(symbolIndex);
        if (position == null) {
            position = new Position(symbolIndex);
            positions.put(symbolIndex, position);
        }
        return position;
    }

    /**
     * Update exposure metrics
     */
    private void updateExposures() {
        grossExposure = 0;
        netExposure = 0;

        positions.forEach((symbolIndex, position) -> {
            grossExposure += Math.abs(position.netPosition) * position.lastMarkPrice;
            netExposure += position.netPosition * position.lastMarkPrice;
        });
    }

    /**
     * Get total P&L (realized + unrealized)
     */
    public long getTotalPnL() {
        return realizedPnL + unrealizedPnL;
    }

    // Configuration
    public void setMaxPosition(long max) { this.maxPosition = max; }
    public void setMaxNotional(long max) { this.maxNotional = max; }

    // Getters
    public long getRealizedPnL() { return realizedPnL; }
    public long getUnrealizedPnL() { return unrealizedPnL; }
    public long getTotalNotional() { return totalNotional; }
    public long getGrossExposure() { return grossExposure; }
    public long getNetExposure() { return netExposure; }
    public int getPositionCount() { return positions.size(); }

    /**
     * Position - Per-symbol position tracking
     */
    public static class Position {
        public final int symbolIndex;

        // Position
        public long netPosition;
        public long avgEntryPrice;

        // P&L
        public long realizedPnL;
        public long unrealizedPnL;
        public long lastMarkPrice;

        // Volume
        public long buyQuantity;
        public long buyNotional;
        public long sellQuantity;
        public long sellNotional;
        public long totalNotional;
        public int tradeCount;

        // Timing
        public long lastUpdateTimestamp;

        public Position(int symbolIndex) {
            this.symbolIndex = symbolIndex;
        }

        public long getTotalPnL() {
            return realizedPnL + unrealizedPnL;
        }

        public double getImbalance() {
            long total = buyQuantity + sellQuantity;
            return total > 0 ? (double)(buyQuantity - sellQuantity) / total : 0;
        }

        @Override
        public String toString() {
            return String.format("Position[symbol=%d, net=%d, avgEntry=%d, pnl=%d/%d]",
                    symbolIndex, netPosition, avgEntryPrice, realizedPnL, unrealizedPnL);
        }
    }
}
