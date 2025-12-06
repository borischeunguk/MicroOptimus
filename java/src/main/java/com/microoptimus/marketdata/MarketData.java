package com.microoptimus.marketdata;

import java.time.Instant;

/**
 * Market data snapshot
 */
public class MarketData {
    private final String symbol;
    private final double bidPrice;
    private final long bidSize;
    private final double askPrice;
    private final long askSize;
    private final double lastPrice;
    private final long lastSize;
    private final Instant timestamp;

    public MarketData(String symbol, double bidPrice, long bidSize,
                      double askPrice, long askSize, double lastPrice, long lastSize) {
        this.symbol = symbol;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
        this.lastPrice = lastPrice;
        this.lastSize = lastSize;
        this.timestamp = Instant.now();
    }

    public String getSymbol() { return symbol; }
    public double getBidPrice() { return bidPrice; }
    public long getBidSize() { return bidSize; }
    public double getAskPrice() { return askPrice; }
    public long getAskSize() { return askSize; }
    public double getLastPrice() { return lastPrice; }
    public long getLastSize() { return lastSize; }
    public Instant getTimestamp() { return timestamp; }

    public double getMidPrice() {
        if (bidPrice > 0 && askPrice > 0) {
            return (bidPrice + askPrice) / 2.0;
        }
        return 0.0;
    }

    public double getSpread() {
        if (bidPrice > 0 && askPrice > 0) {
            return askPrice - bidPrice;
        }
        return 0.0;
    }

    @Override
    public String toString() {
        return String.format("%s: Bid=%.2f(%d) Ask=%.2f(%d) Last=%.2f(%d) [%s]",
                symbol, bidPrice, bidSize, askPrice, askSize, lastPrice, lastSize, timestamp);
    }
}
