package com.microoptimus.common.events.disruptor;

/**
 * BookUpdateEvent - Disruptor event for market data updates (Recombinor → OSM)
 *
 * Pre-allocated, mutable event object used in LMAX Disruptor RingBuffer.
 * Represents a reconstructed orderbook state update from market data.
 *
 * Design: GC-free via object pooling (Disruptor pre-allocates all events)
 */
public class BookUpdateEvent {

    // Symbol (max 8 chars for efficiency, e.g., "AAPL", "ES", "NQ")
    private String symbol;

    // Best Bid/Ask prices (scaled long, e.g., 15000 = $150.00)
    private long bestBidPrice;
    private long bestAskPrice;

    // Best Bid/Ask sizes
    private long bestBidSize;
    private long bestAskSize;

    // Timestamp when event was created (nanoseconds)
    private long timestamp;

    // Sequence number for ordering
    private long sequenceNumber;

    // Book state indicator
    private BookState bookState;

    public enum BookState {
        EMPTY,      // No orders on either side
        ONESIDED,   // Only bid or only ask
        NORMAL,     // Both sides present, not crossed
        LOCKED,     // Bid == Ask
        CROSSED     // Bid > Ask (error condition)
    }

    /**
     * Default constructor (required by Disruptor)
     */
    public BookUpdateEvent() {
        this.bookState = BookState.EMPTY;
    }

    /**
     * Reset event for reuse (called by Disruptor after processing)
     */
    public void clear() {
        this.symbol = null;
        this.bestBidPrice = 0;
        this.bestAskPrice = 0;
        this.bestBidSize = 0;
        this.bestAskSize = 0;
        this.timestamp = 0;
        this.sequenceNumber = 0;
        this.bookState = BookState.EMPTY;
    }

    /**
     * Set all fields at once (called by producer)
     */
    public void set(String symbol, long bestBidPrice, long bestAskPrice,
                    long bestBidSize, long bestAskSize, long timestamp, long sequenceNumber) {
        this.symbol = symbol;
        this.bestBidPrice = bestBidPrice;
        this.bestAskPrice = bestAskPrice;
        this.bestBidSize = bestBidSize;
        this.bestAskSize = bestAskSize;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;

        // Determine book state
        if (bestBidSize == 0 && bestAskSize == 0) {
            this.bookState = BookState.EMPTY;
        } else if (bestBidSize == 0 || bestAskSize == 0) {
            this.bookState = BookState.ONESIDED;
        } else if (bestBidPrice > bestAskPrice) {
            this.bookState = BookState.CROSSED;
        } else if (bestBidPrice == bestAskPrice) {
            this.bookState = BookState.LOCKED;
        } else {
            this.bookState = BookState.NORMAL;
        }
    }

    // Getters
    public String getSymbol() { return symbol; }
    public long getBestBidPrice() { return bestBidPrice; }
    public long getBestAskPrice() { return bestAskPrice; }
    public long getBestBidSize() { return bestBidSize; }
    public long getBestAskSize() { return bestAskSize; }
    public long getTimestamp() { return timestamp; }
    public long getSequenceNumber() { return sequenceNumber; }
    public BookState getBookState() { return bookState; }

    // Calculated fields
    public long getSpread() {
        if (bestBidSize > 0 && bestAskSize > 0) {
            return bestAskPrice - bestBidPrice;
        }
        return Long.MAX_VALUE; // Invalid spread
    }

    public long getMidPrice() {
        if (bestBidSize > 0 && bestAskSize > 0) {
            return (bestBidPrice + bestAskPrice) / 2;
        }
        return 0; // Invalid mid
    }

    @Override
    public String toString() {
        return String.format("BookUpdate[symbol=%s, bid=%d@%d, ask=%d@%d, spread=%d, state=%s, seq=%d, ts=%d]",
                symbol, bestBidSize, bestBidPrice, bestAskSize, bestAskPrice,
                getSpread(), bookState, sequenceNumber, timestamp);
    }
}

