package com.microoptimus.common.events;
}
    }
        this.timestamp = 0;
        this.takerClientId = 0;
        this.makerClientId = 0;
        this.takerOrderId = 0;
        this.makerOrderId = 0;
        this.aggressorSide = null;
        this.executedQuantity = 0;
        this.executedPrice = 0;
        this.symbol = null;
        this.executionId = 0;
    public void reset() {
     */
     * Reset for object pooling (GC-free)
    /**

    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public long getTimestamp() { return timestamp; }

    public void setTakerClientId(long takerClientId) { this.takerClientId = takerClientId; }
    public long getTakerClientId() { return takerClientId; }

    public void setMakerClientId(long makerClientId) { this.makerClientId = makerClientId; }
    public long getMakerClientId() { return makerClientId; }

    public void setTakerOrderId(long takerOrderId) { this.takerOrderId = takerOrderId; }
    public long getTakerOrderId() { return takerOrderId; }

    public void setMakerOrderId(long makerOrderId) { this.makerOrderId = makerOrderId; }
    public long getMakerOrderId() { return makerOrderId; }

    public void setAggressorSide(Side aggressorSide) { this.aggressorSide = aggressorSide; }
    public Side getAggressorSide() { return aggressorSide; }

    public void setExecutedQuantity(long executedQuantity) { this.executedQuantity = executedQuantity; }
    public long getExecutedQuantity() { return executedQuantity; }

    public void setExecutedPrice(long executedPrice) { this.executedPrice = executedPrice; }
    public long getExecutedPrice() { return executedPrice; }

    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSymbol() { return symbol; }

    public void setExecutionId(long executionId) { this.executionId = executionId; }
    public long getExecutionId() { return executionId; }
    // Getters and setters

    private long timestamp;

    private long takerClientId;
    private long makerClientId;
    private long takerOrderId;
    private long makerOrderId;
    // Maker/Taker details

    private Side aggressorSide;
    private long executedQuantity;
    private long executedPrice;
    private String symbol;
    private long executionId;

public class InternalExecutionEvent {
 */
 * Internal match executions
 * InternalExecutionEvent (RB-4: OSM → Recombinor)
/**

import com.microoptimus.common.types.Side;


