package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;

/**
 * ExecutionReport - Pooled execution report for match results
 *
 * Design principles:
 * - Object pooling via init()/reset() pattern
 * - Contains all information needed for fill reporting
 * - Supports partial and full fills
 */
public class ExecutionReport {

    public enum ExecType {
        NEW,           // Order accepted
        FILL,          // Full fill
        PARTIAL_FILL,  // Partial fill
        CANCELLED,     // Order cancelled
        REJECTED,      // Order rejected
        EXPIRED,       // Order expired
        TRADE          // Trade execution
    }

    // Execution identifiers
    private long execId;
    private long orderId;
    private long matchId;  // Links aggressive and passive fills

    // Order details
    private int symbolIndex;
    private Side side;
    private long orderPrice;

    // Execution details
    private ExecType execType;
    private long execPrice;
    private long execQuantity;
    private long leavesQuantity;
    private long cumQuantity;

    // Counterparty info
    private long counterOrderId;
    private long counterClientId;

    // Timestamps
    private long timestamp;

    // Pool management
    private boolean inPool;

    public ExecutionReport() {
        this.inPool = true;
    }

    /**
     * Initialize for a trade execution
     */
    public void initTrade(long execId, long matchId, long orderId, int symbolIndex, Side side,
                          long orderPrice, long execPrice, long execQuantity,
                          long leavesQuantity, long cumQuantity,
                          long counterOrderId, long counterClientId, long timestamp) {
        this.execId = execId;
        this.matchId = matchId;
        this.orderId = orderId;
        this.symbolIndex = symbolIndex;
        this.side = side;
        this.orderPrice = orderPrice;
        this.execType = leavesQuantity == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
        this.execPrice = execPrice;
        this.execQuantity = execQuantity;
        this.leavesQuantity = leavesQuantity;
        this.cumQuantity = cumQuantity;
        this.counterOrderId = counterOrderId;
        this.counterClientId = counterClientId;
        this.timestamp = timestamp;
        this.inPool = false;
    }

    /**
     * Initialize for order status (NEW, CANCELLED, REJECTED, EXPIRED)
     */
    public void initStatus(long execId, long orderId, int symbolIndex, Side side,
                           long orderPrice, ExecType execType,
                           long leavesQuantity, long cumQuantity, long timestamp) {
        this.execId = execId;
        this.matchId = 0;
        this.orderId = orderId;
        this.symbolIndex = symbolIndex;
        this.side = side;
        this.orderPrice = orderPrice;
        this.execType = execType;
        this.execPrice = 0;
        this.execQuantity = 0;
        this.leavesQuantity = leavesQuantity;
        this.cumQuantity = cumQuantity;
        this.counterOrderId = 0;
        this.counterClientId = 0;
        this.timestamp = timestamp;
        this.inPool = false;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.execId = 0;
        this.matchId = 0;
        this.orderId = 0;
        this.symbolIndex = 0;
        this.side = null;
        this.orderPrice = 0;
        this.execType = null;
        this.execPrice = 0;
        this.execQuantity = 0;
        this.leavesQuantity = 0;
        this.cumQuantity = 0;
        this.counterOrderId = 0;
        this.counterClientId = 0;
        this.timestamp = 0;
        this.inPool = true;
    }

    // Getters
    public long getExecId() { return execId; }
    public long getOrderId() { return orderId; }
    public long getMatchId() { return matchId; }
    public int getSymbolIndex() { return symbolIndex; }
    public Side getSide() { return side; }
    public long getOrderPrice() { return orderPrice; }
    public ExecType getExecType() { return execType; }
    public long getExecPrice() { return execPrice; }
    public long getExecQuantity() { return execQuantity; }
    public long getLeavesQuantity() { return leavesQuantity; }
    public long getCumQuantity() { return cumQuantity; }
    public long getCounterOrderId() { return counterOrderId; }
    public long getCounterClientId() { return counterClientId; }
    public long getTimestamp() { return timestamp; }
    public boolean isInPool() { return inPool; }

    // Convenience methods
    public boolean isFill() {
        return execType == ExecType.FILL || execType == ExecType.PARTIAL_FILL || execType == ExecType.TRADE;
    }

    public boolean isFullFill() {
        return execType == ExecType.FILL;
    }

    @Override
    public String toString() {
        return String.format("ExecReport{id=%d, orderId=%d, type=%s, %s %d@%d, leaves=%d}",
                execId, orderId, execType, side, execQuantity, execPrice, leavesQuantity);
    }
}
