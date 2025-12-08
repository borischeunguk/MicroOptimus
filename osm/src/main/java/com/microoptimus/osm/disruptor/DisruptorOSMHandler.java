package com.microoptimus.osm.disruptor;

import com.lmax.disruptor.EventHandler;
import com.microoptimus.common.events.disruptor.BookUpdateEvent;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DisruptorOSMHandler - Consumer that receives BookUpdateEvents from Disruptor
 *
 * Implements EventHandler interface to process events as they arrive.
 * Measures latency from producer timestamp to consumer receipt.
 */
public class DisruptorOSMHandler implements EventHandler<BookUpdateEvent> {

    private static final Logger log = LoggerFactory.getLogger(DisruptorOSMHandler.class);

    // Simple state tracking (no actual matching in V1)
    private String currentSymbol;
    private long currentBestBid;
    private long currentBestAsk;
    private long currentBidSize;
    private long currentAskSize;

    // Metrics
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final Histogram latencyHistogram;
    private boolean measurementPhase = false;
    private long measurementStartTime;
    private long measurementEndTime;

    // Configuration
    private final boolean logEvents;
    private final int logEveryN;

    public DisruptorOSMHandler(boolean logEvents, int logEveryN) {
        this.logEvents = logEvents;
        this.logEveryN = logEveryN;

        // Create histogram with 3 significant digits, max value 1 hour in nanos
        this.latencyHistogram = new Histogram(3_600_000_000_000L, 3);
    }

    public DisruptorOSMHandler() {
        this(false, 10000);
    }

    /**
     * Start measurement phase (after warmup)
     */
    public void startMeasurement() {
        measurementPhase = true;
        measurementStartTime = System.nanoTime();
        latencyHistogram.reset();
        eventsProcessed.set(0);
        log.info("Measurement phase started");
    }

    /**
     * Stop measurement phase
     */
    public void stopMeasurement() {
        measurementEndTime = System.nanoTime();
        measurementPhase = false;
        log.info("Measurement phase stopped");
    }

    @Override
    public void onEvent(BookUpdateEvent event, long sequence, boolean endOfBatch) throws Exception {
        // Record receipt timestamp
        long receiveTime = System.nanoTime();

        // Calculate latency (receive time - event timestamp)
        long latencyNanos = receiveTime - event.getTimestamp();

        // Update internal state
        currentSymbol = event.getSymbol();
        currentBestBid = event.getBestBidPrice();
        currentBestAsk = event.getBestAskPrice();
        currentBidSize = event.getBestBidSize();
        currentAskSize = event.getBestAskSize();

        // Record metrics during measurement phase
        if (measurementPhase && latencyNanos > 0 && latencyNanos < 3_600_000_000_000L) {
            latencyHistogram.recordValue(latencyNanos);
        }

        long processed = eventsProcessed.incrementAndGet();

        // Optional logging
        if (logEvents && (processed % logEveryN == 0)) {
            log.info("Processed event #{}: {} | Latency: {} ns | EndOfBatch: {}",
                    processed, event, latencyNanos, endOfBatch);
        }
    }

    /**
     * Print latency statistics
     */
    public void printStatistics() {
        long totalEvents = eventsProcessed.get();
        long durationNanos = measurementEndTime - measurementStartTime;
        double durationMs = durationNanos / 1_000_000.0;
        double throughput = (totalEvents * 1_000_000_000.0) / durationNanos;

        log.info("=== OSM Handler Statistics ===");
        log.info("Total events processed: {}", totalEvents);
        log.info("Duration: {:.2f} ms", durationMs);
        log.info("Throughput: {:.0f} msgs/sec", throughput);

        if (latencyHistogram.getTotalCount() > 0) {
            log.info("=== Latency Statistics (Recombinor → OSM) ===");
            log.info("  Min:    {} ns", latencyHistogram.getMinValue());
            log.info("  Mean:   {:.0f} ns", latencyHistogram.getMean());
            log.info("  Median: {} ns", latencyHistogram.getValueAtPercentile(50.0));
            log.info("  P90:    {} ns", latencyHistogram.getValueAtPercentile(90.0));
            log.info("  P95:    {} ns", latencyHistogram.getValueAtPercentile(95.0));
            log.info("  P99:    {} ns", latencyHistogram.getValueAtPercentile(99.0));
            log.info("  P99.9:  {} ns", latencyHistogram.getValueAtPercentile(99.9));
            log.info("  P99.99: {} ns", latencyHistogram.getValueAtPercentile(99.99));
            log.info("  Max:    {} ns", latencyHistogram.getMaxValue());
        }

        log.info("=== Current Book State ===");
        log.info("  Symbol: {}", currentSymbol);
        log.info("  Bid: {} @ {}", currentBidSize, currentBestBid);
        log.info("  Ask: {} @ {}", currentAskSize, currentBestAsk);
        log.info("  Spread: {}", (currentBestAsk - currentBestBid));
    }

    // Getters for testing
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    public Histogram getLatencyHistogram() {
        return latencyHistogram;
    }

    public String getCurrentSymbol() {
        return currentSymbol;
    }

    public long getCurrentBestBid() {
        return currentBestBid;
    }

    public long getCurrentBestAsk() {
        return currentBestAsk;
    }
}

