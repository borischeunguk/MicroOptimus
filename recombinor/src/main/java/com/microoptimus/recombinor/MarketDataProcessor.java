package com.microoptimus.recombinor;

import com.lmax.disruptor.RingBuffer;
import com.microoptimus.common.events.ExternalMarketDataEvent;
import com.microoptimus.common.events.InternalExecutionEvent;
import com.microoptimus.common.events.MarketDataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MarketDataProcessor - Combines internal and external market data
 * Maintains consolidated market view
 */
public class MarketDataProcessor {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataProcessor.class);

    private final RingBuffer<MarketDataEvent> marketDataRing;
    private final Map<String, BookState> bookStates;

    public MarketDataProcessor(RingBuffer<MarketDataEvent> marketDataRing) {
        this.marketDataRing = marketDataRing;
        this.bookStates = new HashMap<>();
    }

    /**
     * Process internal execution
     */
    public void onInternalExecution(InternalExecutionEvent event) {
        String symbol = event.getSymbol();
        BookState state = bookStates.computeIfAbsent(symbol, BookState::new);

        // Update book state with internal execution
        state.updateFromInternalExecution(event);

        logger.debug("Processed internal execution for {}: price={}, qty={}",
                symbol, event.getExecutedPrice(), event.getExecutedQuantity());
    }

    /**
     * Process external market data from CME
     */
    public void onExternalMarketData(ExternalMarketDataEvent event) {
        String symbol = event.getSymbol();
        BookState state = bookStates.computeIfAbsent(symbol, BookState::new);

        // Update book state with external market data
        state.updateFromExternalMarketData(event);

        logger.debug("Processed external market data for {}", symbol);
    }

    /**
     * Publish consolidated market data
     */
    public void publishMarketData(String symbol) {
        BookState state = bookStates.get(symbol);
        if (state == null) return;

        long seq = marketDataRing.next();
        try {
            MarketDataEvent event = marketDataRing.get(seq);
            state.populateMarketDataEvent(event);
            event.setSource(MarketDataEvent.Source.COMBINED);
            event.setTimestamp(System.nanoTime());
        } finally {
            marketDataRing.publish(seq);
        }
    }

    /**
     * Internal book state tracker
     */
    private static class BookState {
        private final String symbol;
        private long bidPrice;
        private long bidSize;
        private long askPrice;
        private long askSize;
        private long lastTradePrice;
        private long lastTradeSize;

        BookState(String symbol) {
            this.symbol = symbol;
        }

        void updateFromInternalExecution(InternalExecutionEvent event) {
            this.lastTradePrice = event.getExecutedPrice();
            this.lastTradeSize = event.getExecutedQuantity();
        }

        void updateFromExternalMarketData(ExternalMarketDataEvent event) {
            this.bidPrice = event.getBidPrice();
            this.bidSize = event.getBidSize();
            this.askPrice = event.getAskPrice();
            this.askSize = event.getAskSize();
        }

        void populateMarketDataEvent(MarketDataEvent event) {
            event.setSymbol(symbol);
            event.setBidPrice(bidPrice);
            event.setBidSize(bidSize);
            event.setAskPrice(askPrice);
            event.setAskSize(askSize);
            event.setLastTradePrice(lastTradePrice);
            event.setLastTradeSize(lastTradeSize);
        }
    }
}

