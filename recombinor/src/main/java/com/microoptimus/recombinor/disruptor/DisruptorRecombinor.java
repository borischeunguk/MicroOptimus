package com.microoptimus.recombinor.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.microoptimus.common.events.disruptor.BookUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DisruptorRecombinor - Producer that publishes market data updates to Disruptor RingBuffer
 *
 * Simulates a market data processor that reconstructs orderbook from CME updates
 * and publishes BookUpdateEvents to OSM via Disruptor.
 */
public class DisruptorRecombinor {

    private static final Logger log = LoggerFactory.getLogger(DisruptorRecombinor.class);

    private final RingBuffer<BookUpdateEvent> ringBuffer;
    private final String symbol;
    private long sequenceCounter = 0;

    public DisruptorRecombinor(RingBuffer<BookUpdateEvent> ringBuffer, String symbol) {
        this.ringBuffer = ringBuffer;
        this.symbol = symbol;
    }

    /**
     * Publish a book update event
     *
     * @param bidPrice Best bid price (scaled long)
     * @param bidSize Best bid size
     * @param askPrice Best ask price (scaled long)
     * @param askSize Best ask size
     * @return The sequence number assigned to this event
     */
    public long publishBookUpdate(long bidPrice, long bidSize, long askPrice, long askSize) {
        // Get next sequence from RingBuffer
        long sequence = ringBuffer.next();

        try {
            // Get pre-allocated event at this sequence
            BookUpdateEvent event = ringBuffer.get(sequence);

            // Record timestamp BEFORE setting data (measures producer-side latency)
            long timestamp = System.nanoTime();

            // Set event data
            event.set(symbol, bidPrice, askPrice, bidSize, askSize, timestamp, sequenceCounter++);

        } finally {
            // Publish (make visible to consumers)
            ringBuffer.publish(sequence);
        }

        return sequence;
    }

    /**
     * Generate synthetic market data for testing
     *
     * @param count Number of updates to generate
     * @param delayNanos Delay between updates (0 = no delay, publish as fast as possible)
     */
    public void generateSyntheticData(int count, long delayNanos) {
        log.info("Generating {} synthetic book updates for symbol: {}", count, symbol);

        long startTime = System.nanoTime();

        // Starting prices
        long bidPrice = 150_00; // $150.00 (scaled by 100)
        long askPrice = 150_10; // $150.10

        for (int i = 0; i < count; i++) {
            // Simulate price movement (random walk)
            if (i % 10 == 0) {
                bidPrice += (i % 20 == 0) ? -5 : 5;
                askPrice = bidPrice + 10; // Keep 10 cent spread
            }

            // Publish update
            publishBookUpdate(bidPrice, 100, askPrice, 100);

            // Optional delay
            if (delayNanos > 0) {
                long target = System.nanoTime() + delayNanos;
                while (System.nanoTime() < target) {
                    // Busy spin
                }
            }
        }

        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMs = durationNanos / 1_000_000.0;
        double throughput = (count * 1_000_000_000.0) / durationNanos;

        log.info("Published {} events in {:.2f} ms ({:.0f} msgs/sec)",
                count, durationMs, throughput);
    }

    /**
     * Warmup phase - send events to warm up JIT compiler
     */
    public void warmup(int warmupCount) {
        log.info("Starting warmup phase with {} events", warmupCount);
        generateSyntheticData(warmupCount, 0);
        log.info("Warmup complete");
    }

    public String getSymbol() {
        return symbol;
    }

    public long getSequenceCounter() {
        return sequenceCounter;
    }
}

