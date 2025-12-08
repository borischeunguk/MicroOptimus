package com.microoptimus.app.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.microoptimus.common.events.disruptor.BookUpdateEvent;
import com.microoptimus.osm.disruptor.DisruptorOSMHandler;
import com.microoptimus.recombinor.disruptor.DisruptorRecombinor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DisruptorApp - Version 1 Main Launcher
 *
 * Single-process application demonstrating Disruptor-based messaging
 * between Recombinor (producer) and OSM (consumer).
 *
 * Flow: Recombinor → Disruptor RingBuffer → OSM Handler
 */
public class DisruptorApp {

    private static final Logger log = LoggerFactory.getLogger(DisruptorApp.class);

    // Configuration
    private static final int RING_BUFFER_SIZE = 2048; // Must be power of 2
    private static final int WARMUP_EVENTS = 100_000;
    private static final int MEASUREMENT_EVENTS = 1_000_000;
    private static final String SYMBOL = "AAPL";

    public static void main(String[] args) throws Exception {
        log.info("=== MicroOptimus Version 1: LMAX Disruptor ===");
        log.info("Ring Buffer Size: {}", RING_BUFFER_SIZE);
        log.info("Warmup Events: {}", WARMUP_EVENTS);
        log.info("Measurement Events: {}", MEASUREMENT_EVENTS);
        log.info("Symbol: {}", SYMBOL);

        // Create Disruptor
        log.info("Creating Disruptor...");
        Disruptor<BookUpdateEvent> disruptor = new Disruptor<>(
                BookUpdateEvent::new,           // Event factory
                RING_BUFFER_SIZE,                // Ring buffer size (power of 2)
                DaemonThreadFactory.INSTANCE,    // Thread factory
                ProducerType.SINGLE,             // Single producer (Recombinor)
                new BusySpinWaitStrategy()       // Lowest latency wait strategy
        );

        // Create OSM handler (consumer)
        DisruptorOSMHandler osmHandler = new DisruptorOSMHandler(false, 100_000);

        // Wire up handler
        disruptor.handleEventsWith(osmHandler);

        // Start Disruptor
        log.info("Starting Disruptor...");
        disruptor.start();

        // Get RingBuffer reference for producer
        RingBuffer<BookUpdateEvent> ringBuffer = disruptor.getRingBuffer();

        // Create Recombinor (producer)
        DisruptorRecombinor recombinor = new DisruptorRecombinor(ringBuffer, SYMBOL);

        // Allow threads to settle
        Thread.sleep(100);

        // === WARMUP PHASE ===
        log.info("\n=== WARMUP PHASE ===");
        recombinor.warmup(WARMUP_EVENTS);

        // Wait for all warmup events to be processed
        waitForProcessing(osmHandler, WARMUP_EVENTS, 5000);

        log.info("Warmup complete. Events processed: {}", osmHandler.getEventsProcessed());

        // Small pause
        Thread.sleep(500);

        // === MEASUREMENT PHASE ===
        log.info("\n=== MEASUREMENT PHASE ===");

        // Start measurement
        osmHandler.startMeasurement();

        long startTime = System.nanoTime();

        // Generate measurement events
        recombinor.generateSyntheticData(MEASUREMENT_EVENTS, 0);

        // Wait for all events to be processed
        long initialCount = osmHandler.getEventsProcessed();
        waitForProcessing(osmHandler, initialCount + MEASUREMENT_EVENTS, 10000);

        long endTime = System.nanoTime();

        // Stop measurement
        osmHandler.stopMeasurement();

        // Print results
        log.info("\n=== BENCHMARK RESULTS ===");
        long totalDurationNanos = endTime - startTime;
        double totalDurationMs = totalDurationNanos / 1_000_000.0;
        double overallThroughput = (MEASUREMENT_EVENTS * 1_000_000_000.0) / totalDurationNanos;

        log.info("Total end-to-end duration: {:.2f} ms", totalDurationMs);
        log.info("Overall throughput: {:.0f} msgs/sec", overallThroughput);

        osmHandler.printStatistics();

        // Shutdown
        log.info("\n=== Shutting down ===");
        disruptor.shutdown();

        log.info("Disruptor shutdown complete");
        log.info("=== Version 1 Complete ===");
    }

    /**
     * Wait for OSM handler to process expected number of events
     */
    private static void waitForProcessing(DisruptorOSMHandler handler, long expectedCount, long timeoutMs)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        while (handler.getEventsProcessed() < expectedCount) {
            Thread.sleep(10);
            if (System.currentTimeMillis() - start > timeoutMs) {
                log.warn("Timeout waiting for processing. Expected: {}, Actual: {}",
                        expectedCount, handler.getEventsProcessed());
                break;
            }
        }
    }
}

