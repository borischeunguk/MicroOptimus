package com.microoptimus.algo.model;

import com.microoptimus.common.types.Side;
import com.microoptimus.algo.state.AlgoOrderState;

/**
 * AlgoOrder - Pooled algorithmic order object
 *
 * Represents a parent algo order that generates child slices.
 */
public class AlgoOrder {

    // Identifiers
    private long algoOrderId;
    private long clientId;
    private int symbolIndex;
    private String symbol;

    // Order attributes
    private Side side;
    private long totalQuantity;
    private long limitPrice;  // 0 for market
    private AlgorithmType algorithmType;

    // Execution progress
    private long executedQuantity;
    private long leavesQuantity;
    private long avgPrice;
    private int slicesSent;
    private int slicesFilled;
    private int slicesCancelled;

    // Algorithm parameters
    private AlgoParameters parameters;

    // Timing
    private long startTime;
    private long endTime;
    private long createTimestamp;
    private long lastUpdateTimestamp;

    // State
    private AlgoOrderState state;
    private String rejectReason;

    // Pool management
    private boolean inPool;

    public enum AlgorithmType {
        VWAP,
        TWAP,
        ICEBERG,
        POV,       // Percentage of Volume
        IS         // Implementation Shortfall
    }

    public AlgoOrder() {
        this.state = AlgoOrderState.PENDING;
        this.inPool = true;
    }

    /**
     * Initialize algo order
     */
    public void init(long algoOrderId, long clientId, int symbolIndex, Side side,
                     long totalQuantity, long limitPrice, AlgorithmType algorithmType,
                     long startTime, long endTime, long timestamp) {
        this.algoOrderId = algoOrderId;
        this.clientId = clientId;
        this.symbolIndex = symbolIndex;
        this.symbol = null;
        this.side = side;
        this.totalQuantity = totalQuantity;
        this.limitPrice = limitPrice;
        this.algorithmType = algorithmType;
        this.executedQuantity = 0;
        this.leavesQuantity = totalQuantity;
        this.avgPrice = 0;
        this.slicesSent = 0;
        this.slicesFilled = 0;
        this.slicesCancelled = 0;
        this.parameters = null;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createTimestamp = timestamp;
        this.lastUpdateTimestamp = timestamp;
        this.state = AlgoOrderState.PENDING;
        this.rejectReason = null;
        this.inPool = false;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.algoOrderId = 0;
        this.clientId = 0;
        this.symbolIndex = 0;
        this.symbol = null;
        this.side = null;
        this.totalQuantity = 0;
        this.limitPrice = 0;
        this.algorithmType = null;
        this.executedQuantity = 0;
        this.leavesQuantity = 0;
        this.avgPrice = 0;
        this.slicesSent = 0;
        this.slicesFilled = 0;
        this.slicesCancelled = 0;
        this.parameters = null;
        this.startTime = 0;
        this.endTime = 0;
        this.createTimestamp = 0;
        this.lastUpdateTimestamp = 0;
        this.state = AlgoOrderState.PENDING;
        this.rejectReason = null;
        this.inPool = true;
    }

    /**
     * Record a slice fill
     */
    public void onSliceFill(long quantity, long price, long timestamp) {
        // Update VWAP
        long totalValue = (avgPrice * executedQuantity) + (price * quantity);
        executedQuantity += quantity;
        leavesQuantity -= quantity;
        avgPrice = executedQuantity > 0 ? totalValue / executedQuantity : 0;
        lastUpdateTimestamp = timestamp;

        if (leavesQuantity == 0) {
            state = AlgoOrderState.FILLED;
        } else if (executedQuantity > 0) {
            state = AlgoOrderState.PARTIAL_FILL;
        }
    }

    /**
     * Record slice sent
     */
    public void onSliceSent() {
        slicesSent++;
    }

    /**
     * Record slice filled
     */
    public void onSliceFilled() {
        slicesFilled++;
    }

    /**
     * Record slice cancelled
     */
    public void onSliceCancelled() {
        slicesCancelled++;
    }

    // State transitions
    public void start(long timestamp) {
        this.state = AlgoOrderState.WORKING;
        this.lastUpdateTimestamp = timestamp;
    }

    public void pause(long timestamp) {
        this.state = AlgoOrderState.PAUSED;
        this.lastUpdateTimestamp = timestamp;
    }

    public void resume(long timestamp) {
        this.state = AlgoOrderState.WORKING;
        this.lastUpdateTimestamp = timestamp;
    }

    public void cancel(long timestamp) {
        this.state = AlgoOrderState.CANCELLED;
        this.lastUpdateTimestamp = timestamp;
    }

    public void reject(String reason, long timestamp) {
        this.state = AlgoOrderState.REJECTED;
        this.rejectReason = reason;
        this.lastUpdateTimestamp = timestamp;
    }

    public void expire(long timestamp) {
        this.state = AlgoOrderState.EXPIRED;
        this.lastUpdateTimestamp = timestamp;
    }

    // Getters
    public long getAlgoOrderId() { return algoOrderId; }
    public long getClientId() { return clientId; }
    public int getSymbolIndex() { return symbolIndex; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public long getTotalQuantity() { return totalQuantity; }
    public long getLimitPrice() { return limitPrice; }
    public AlgorithmType getAlgorithmType() { return algorithmType; }
    public long getExecutedQuantity() { return executedQuantity; }
    public long getLeavesQuantity() { return leavesQuantity; }
    public long getAvgPrice() { return avgPrice; }
    public int getSlicesSent() { return slicesSent; }
    public int getSlicesFilled() { return slicesFilled; }
    public int getSlicesCancelled() { return slicesCancelled; }
    public AlgoParameters getParameters() { return parameters; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getCreateTimestamp() { return createTimestamp; }
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public AlgoOrderState getState() { return state; }
    public String getRejectReason() { return rejectReason; }
    public boolean isInPool() { return inPool; }

    // Setters
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setParameters(AlgoParameters params) { this.parameters = params; }

    // State queries
    public boolean isActive() {
        return state == AlgoOrderState.WORKING || state == AlgoOrderState.PAUSED;
    }

    public boolean isTerminal() {
        return state == AlgoOrderState.FILLED ||
               state == AlgoOrderState.CANCELLED ||
               state == AlgoOrderState.REJECTED ||
               state == AlgoOrderState.EXPIRED;
    }

    public double getCompletionRate() {
        return totalQuantity > 0 ? (double) executedQuantity / totalQuantity : 0;
    }

    @Override
    public String toString() {
        return String.format("AlgoOrder{id=%d, %s %s %d/%d@%d, state=%s, slices=%d/%d}",
                algoOrderId, algorithmType, side, executedQuantity, totalQuantity,
                avgPrice, state, slicesFilled, slicesSent);
    }
}
