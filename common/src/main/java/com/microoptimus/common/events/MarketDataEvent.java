package com.microoptimus.common.events;

import com.microoptimus.common.types.Side;

/**
 * MarketDataEvent (RB-5: Recombinor → OSM-Signal)
 * Consolidated market data
 */
public class MarketDataEvent {

    public enum Source {
        INTERNAL,
        EXTERNAL,
        COMBINED
    }

    private Source source;
    private String symbol;

    // Top of book
    private long bidPrice;
    private long bidSize;
    private long askPrice;
    private long askSize;

    // Last trade
    private long lastTradePrice;
    private long lastTradeSize;
    private Side lastTradeSide;

    // Book imbalance (for strategies)
    private double imbalanceRatio;

    private long timestamp;

    // Getters and setters
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public long getBidPrice() { return bidPrice; }
    public void setBidPrice(long bidPrice) { this.bidPrice = bidPrice; }

    public long getBidSize() { return bidSize; }
    public void setBidSize(long bidSize) { this.bidSize = bidSize; }

    public long getAskPrice() { return askPrice; }
    public void setAskPrice(long askPrice) { this.askPrice = askPrice; }

    public long getAskSize() { return askSize; }
    public void setAskSize(long askSize) { this.askSize = askSize; }

    public long getLastTradePrice() { return lastTradePrice; }
    public void setLastTradePrice(long lastTradePrice) { this.lastTradePrice = lastTradePrice; }

    public long getLastTradeSize() { return lastTradeSize; }
    public void setLastTradeSize(long lastTradeSize) { this.lastTradeSize = lastTradeSize; }

    public Side getLastTradeSide() { return lastTradeSide; }
    public void setLastTradeSide(Side lastTradeSide) { this.lastTradeSide = lastTradeSide; }

    public double getImbalanceRatio() { return imbalanceRatio; }
    public void setImbalanceRatio(double imbalanceRatio) { this.imbalanceRatio = imbalanceRatio; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.source = null;
        this.symbol = null;
        this.bidPrice = 0;
        this.bidSize = 0;
        this.askPrice = 0;
        this.askSize = 0;
        this.lastTradePrice = 0;
        this.lastTradeSize = 0;
        this.lastTradeSide = null;
        this.imbalanceRatio = 0.0;
        this.timestamp = 0;
    }
}

