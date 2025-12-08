package com.microoptimus.common.events;

import com.microoptimus.common.types.Side;

/**
 * InternalExecutionEvent (RB-4: OSM → Recombinor)
 * Internal match executions
 */
public class InternalExecutionEvent {

    private long executionId;
    private String symbol;
    private long executedPrice;
    private long executedQuantity;
    private Side aggressorSide;

    // Maker/Taker details
    private long makerOrderId;
    private long takerOrderId;
    private long makerClientId;
    private long takerClientId;

    private long timestamp;

    // Getters and setters
    public long getExecutionId() { return executionId; }
    public void setExecutionId(long executionId) { this.executionId = executionId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public long getExecutedPrice() { return executedPrice; }
    public void setExecutedPrice(long executedPrice) { this.executedPrice = executedPrice; }

    public long getExecutedQuantity() { return executedQuantity; }
    public void setExecutedQuantity(long executedQuantity) { this.executedQuantity = executedQuantity; }

    public Side getAggressorSide() { return aggressorSide; }
    public void setAggressorSide(Side aggressorSide) { this.aggressorSide = aggressorSide; }

    public long getMakerOrderId() { return makerOrderId; }
    public void setMakerOrderId(long makerOrderId) { this.makerOrderId = makerOrderId; }

    public long getTakerOrderId() { return takerOrderId; }
    public void setTakerOrderId(long takerOrderId) { this.takerOrderId = takerOrderId; }

    public long getMakerClientId() { return makerClientId; }
    public void setMakerClientId(long makerClientId) { this.makerClientId = makerClientId; }

    public long getTakerClientId() { return takerClientId; }
    public void setTakerClientId(long takerClientId) { this.takerClientId = takerClientId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.executionId = 0;
        this.symbol = null;
        this.executedPrice = 0;
        this.executedQuantity = 0;
        this.aggressorSide = null;
        this.makerOrderId = 0;
        this.takerOrderId = 0;
        this.makerClientId = 0;
        this.takerClientId = 0;
        this.timestamp = 0;
    }
}

