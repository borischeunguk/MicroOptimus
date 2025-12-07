package com.microoptimus.common.events;

import com.microoptimus.common.types.Side;

/**
 * ExternalExecutionEvent (RB-3: Liquidator → OSM)
 * CME execution reports (fills, cancels, rejects)
 */
public class ExternalExecutionEvent {

    public enum Type {
        FILL,
        PARTIAL_FILL,
        CANCEL,
        REJECT
    }

    private Type type;
    private long internalOrderId;
    private long cmeOrderId;
    private long cmeExecutionId;
    private String symbol;
    private Side side;
    private long executedPrice;
    private long executedQuantity;
    private long remainingQuantity;

    // For rejects
    private String rejectReason;
    private int rejectCode;

    private long exchangeTimestamp;
    private long receiveTimestamp;

    // Getters and setters
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public long getInternalOrderId() { return internalOrderId; }
    public void setInternalOrderId(long internalOrderId) { this.internalOrderId = internalOrderId; }

    public long getCmeOrderId() { return cmeOrderId; }
    public void setCmeOrderId(long cmeOrderId) { this.cmeOrderId = cmeOrderId; }

    public long getCmeExecutionId() { return cmeExecutionId; }
    public void setCmeExecutionId(long cmeExecutionId) { this.cmeExecutionId = cmeExecutionId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }

    public long getExecutedPrice() { return executedPrice; }
    public void setExecutedPrice(long executedPrice) { this.executedPrice = executedPrice; }

    public long getExecutedQuantity() { return executedQuantity; }
    public void setExecutedQuantity(long executedQuantity) { this.executedQuantity = executedQuantity; }

    public long getRemainingQuantity() { return remainingQuantity; }
    public void setRemainingQuantity(long remainingQuantity) { this.remainingQuantity = remainingQuantity; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public int getRejectCode() { return rejectCode; }
    public void setRejectCode(int rejectCode) { this.rejectCode = rejectCode; }

    public long getExchangeTimestamp() { return exchangeTimestamp; }
    public void setExchangeTimestamp(long exchangeTimestamp) { this.exchangeTimestamp = exchangeTimestamp; }

    public long getReceiveTimestamp() { return receiveTimestamp; }
    public void setReceiveTimestamp(long receiveTimestamp) { this.receiveTimestamp = receiveTimestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.type = null;
        this.internalOrderId = 0;
        this.cmeOrderId = 0;
        this.cmeExecutionId = 0;
        this.symbol = null;
        this.side = null;
        this.executedPrice = 0;
        this.executedQuantity = 0;
        this.remainingQuantity = 0;
        this.rejectReason = null;
        this.rejectCode = 0;
        this.exchangeTimestamp = 0;
        this.receiveTimestamp = 0;
    }
}

