package com.microoptimus.signal.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.microoptimus.common.events.disruptor.BookUpdateEvent;
import com.microoptimus.common.events.disruptor.OrderRequestEvent;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DisruptorSignalHandler - Market Making Strategy Handler
 *
 * Receives market data from Recombinor via RingBuffer-1
 * Generates order requests to OSM via RingBuffer-2
 *
 * Flow: BookUpdateEvent → Market Making Logic → OrderRequestEvent
 */
public class DisruptorSignalHandler implements EventHandler<BookUpdateEvent> {

    private static final Logger log = LoggerFactory.getLogger(DisruptorSignalHandler.class);

    // Output ring buffer for orders to OSM
    private final RingBuffer<OrderRequestEvent> orderRingBuffer;

    // Strategy parameters
    private final String symbol;
    private final long clientId = 1; // Market maker client ID
    private final long quoteSize;
    private final long spreadTicks;
    private final long skewTicks;

    // Order ID generator
    private long nextOrderId = 1;

    // Current market state
    private long currentMidPrice = 0;
    private long lastBidPrice = 0;
    private long lastAskPrice = 0;

    // Metrics
    private final AtomicLong marketDataReceived = new AtomicLong(0);
    private final AtomicLong ordersGenerated = new AtomicLong(0);

    // Configuration
    private final boolean generateQuotes;
    private final int quoteFrequency; // Generate quotes every N market data updates

    /**
     * Create signal handler with order ring buffer
     *
     * @param orderRingBuffer Ring buffer to publish orders to OSM
     * @param symbol Trading symbol
     * @param quoteSize Size per quote
     * @param spreadTicks Spread in ticks
     * @param generateQuotes Whether to generate quotes
     * @param quoteFrequency Generate quotes every N updates (1 = every update)
     */
    public DisruptorSignalHandler(RingBuffer<OrderRequestEvent> orderRingBuffer,
                                   String symbol,
                                   long quoteSize,
                                   long spreadTicks,
                                   boolean generateQuotes,
                                   int quoteFrequency) {
        this.orderRingBuffer = orderRingBuffer;
        this.symbol = symbol;
        this.quoteSize = quoteSize;
        this.spreadTicks = spreadTicks;
        this.skewTicks = 0; // No skew for now
        this.generateQuotes = generateQuotes;
        this.quoteFrequency = quoteFrequency;
    }

    public DisruptorSignalHandler(RingBuffer<OrderRequestEvent> orderRingBuffer, String symbol) {
        this(orderRingBuffer, symbol, 100, 10, true, 1000);
    }

    @Override
    public void onEvent(BookUpdateEvent event, long sequence, boolean endOfBatch) throws Exception {
        long receiveTime = System.nanoTime();

        // Update market state
        long bidPrice = event.getBestBidPrice();
        long askPrice = event.getBestAskPrice();

        if (bidPrice > 0 && askPrice > 0) {
            currentMidPrice = (bidPrice + askPrice) / 2;
            lastBidPrice = bidPrice;
            lastAskPrice = askPrice;
        }

        long count = marketDataReceived.incrementAndGet();

        // Generate quotes based on frequency
        if (generateQuotes && bidPrice > 0 && askPrice > 0) {
            if (count % quoteFrequency == 0) {
                generateQuotes(event, receiveTime);
            }
        }
    }

    /**
     * Generate bid and ask quotes based on current market data
     */
    private void generateQuotes(BookUpdateEvent marketData, long timestamp) {
        // Calculate quote prices (simple market making)
        // Place quotes inside the current market
        long quoteBidPrice = currentMidPrice - (spreadTicks / 2) + skewTicks;
        long quoteAskPrice = currentMidPrice + (spreadTicks / 2) + skewTicks;

        // Ensure we don't cross the market
        if (quoteBidPrice >= lastAskPrice) {
            quoteBidPrice = lastAskPrice - 1;
        }
        if (quoteAskPrice <= lastBidPrice) {
            quoteAskPrice = lastBidPrice + 1;
        }

        // Generate bid order
        publishOrder(Side.BUY, quoteBidPrice, quoteSize, timestamp);

        // Generate ask order
        publishOrder(Side.SELL, quoteAskPrice, quoteSize, timestamp);
    }

    /**
     * Publish order to OSM via ring buffer
     */
    private void publishOrder(Side side, long price, long quantity, long timestamp) {
        long sequence = orderRingBuffer.next();
        try {
            OrderRequestEvent event = orderRingBuffer.get(sequence);

            long orderId = nextOrderId++;

            event.setLimitOrder(
                orderId,
                clientId,
                symbol,
                side,
                price,
                quantity,
                TimeInForce.GTC,
                timestamp,
                orderId
            );

            ordersGenerated.incrementAndGet();
        } finally {
            orderRingBuffer.publish(sequence);
        }
    }

    // Metrics getters
    public long getMarketDataReceived() {
        return marketDataReceived.get();
    }

    public long getOrdersGenerated() {
        return ordersGenerated.get();
    }

    public long getCurrentMidPrice() {
        return currentMidPrice;
    }

    public void printStatistics() {
        log.info("=== Signal Handler Statistics ===");
        log.info("Market Data Received: {}", marketDataReceived.get());
        log.info("Orders Generated: {}", ordersGenerated.get());
        log.info("Current Mid Price: {}", currentMidPrice);
        log.info("Last Bid: {}", lastBidPrice);
        log.info("Last Ask: {}", lastAskPrice);
    }
}

