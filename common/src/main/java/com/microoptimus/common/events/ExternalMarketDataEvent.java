package com.microoptimus.common.events;

/**
 * ExternalMarketDataEvent (RB-6: Gateway → Recombinor)
 * Raw CME market data feed
 */
public class ExternalMarketDataEvent {

    public enum Type {
        INCREMENTAL,
        SNAPSHOT,
        TRADE
    }

    private Type type;
    private String symbol;

    // Raw SBE/FIX data (pre-allocated buffer)
    private byte[] rawData = new byte[8192];
    private int dataLength;

    // Decoded fields
    private long bidPrice;
    private long bidSize;
    private long askPrice;
    private long askSize;
    private long lastTradePrice;
    private long lastTradeSize;

    private long sequenceNumber;
    private long exchangeTimestamp;
    private long receiveTimestamp;

    // Getters and setters
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public byte[] getRawData() { return rawData; }
    public int getDataLength() { return dataLength; }
    public void setDataLength(int dataLength) { this.dataLength = dataLength; }

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

    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public long getExchangeTimestamp() { return exchangeTimestamp; }
    public void setExchangeTimestamp(long exchangeTimestamp) { this.exchangeTimestamp = exchangeTimestamp; }

    public long getReceiveTimestamp() { return receiveTimestamp; }
    public void setReceiveTimestamp(long receiveTimestamp) { this.receiveTimestamp = receiveTimestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.type = null;
        this.symbol = null;
        this.dataLength = 0;
        this.bidPrice = 0;
        this.bidSize = 0;
        this.askPrice = 0;
        this.askSize = 0;
        this.lastTradePrice = 0;
        this.lastTradeSize = 0;
        this.sequenceNumber = 0;
        this.exchangeTimestamp = 0;
        this.receiveTimestamp = 0;
        // Note: rawData array is reused, not cleared
    }
}

