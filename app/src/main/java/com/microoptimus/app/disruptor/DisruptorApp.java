package com.microoptimus.app.disruptor;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.microoptimus.common.events.disruptor.BookUpdateEvent;
import com.microoptimus.common.events.disruptor.OrderRequestEvent;
import com.microoptimus.osm.disruptor.DisruptorOSMHandler;
import com.microoptimus.recombinor.disruptor.DisruptorRecombinor;
import com.microoptimus.signal.disruptor.DisruptorSignalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DisruptorApp - Version 1 Main Launcher (Corrected Architecture)
 *
 * Three-stage pipeline with two Disruptor RingBuffers:
 *
 * RingBuffer-1: Market Data (Recombinor → Signal)
 *   - Recombinor publishes BookUpdateEvents
 *   - Signal consumes market data, generates orders
 *
 * RingBuffer-2: Order Requests (Signal → OSM)
 *   - Signal publishes OrderRequestEvents
 *   - OSM consumes orders, matches in OrderBook
 *
 * Flow: Recombinor → [RB-1] → Signal → [RB-2] → OSM
 */
public class DisruptorApp {

    private static final Logger log = LoggerFactory.getLogger(DisruptorApp.class);

    // Configuration
    private static final int RING_BUFFER_SIZE = 2048; // Must be power of 2
    private static final int WARMUP_EVENTS = 1000;
    private static final int MEASUREMENT_EVENTS = 10_000;
    private static final String SYMBOL = "AAPL";

    // Signal strategy parameters
    private static final long QUOTE_SIZE = 100;
    private static final long SPREAD_TICKS = 10;
    private static final int QUOTE_FREQUENCY = 100; // Generate quotes every 100 market data updates

    public static void main(String[] args) throws Exception {
        log.info("=== MicroOptimus Version 1: LMAX Disruptor (Corrected Architecture) ===");
        log.info("Ring Buffer Size: {}", RING_BUFFER_SIZE);
        log.info("Warmup Events: {}", WARMUP_EVENTS);
        log.info("Measurement Events: {}", MEASUREMENT_EVENTS);
        log.info("Symbol: {}", SYMBOL);
        log.info("\nArchitecture: Recombinor → [RB-1: Market Data] → Signal → [RB-2: Orders] → OSM\n");

        // ===================================================================
        // Create RingBuffer-1: Market Data (Recombinor → Signal)
        // ===================================================================
        log.info("Creating RingBuffer-1 (Market Data)...");
        Disruptor<BookUpdateEvent> marketDataDisruptor = new Disruptor<>(
                BookUpdateEvent::new,
                RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BusySpinWaitStrategy()
        );

        // ===================================================================
        // Create RingBuffer-2: Order Requests (Signal → OSM)
        // ===================================================================
        log.info("Creating RingBuffer-2 (Order Requests)...");
        Disruptor<OrderRequestEvent> orderDisruptor = new Disruptor<>(
                OrderRequestEvent::new,
                RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new BusySpinWaitStrategy()
        );

        // ===================================================================
        // Create Handlers
        // ===================================================================

        // OSM Handler - consumes orders from RingBuffer-2
        log.info("Creating OSM Handler...");
        DisruptorOSMHandler osmHandler = new DisruptorOSMHandler(SYMBOL, false, 100_000);

        // Signal Handler - consumes market data from RingBuffer-1, produces orders to RingBuffer-2
        log.info("Creating Signal Handler...");
        RingBuffer<OrderRequestEvent> orderRingBuffer = orderDisruptor.getRingBuffer();
        DisruptorSignalHandler signalHandler = new DisruptorSignalHandler(
                orderRingBuffer,
                SYMBOL,
                QUOTE_SIZE,
                SPREAD_TICKS,
                true, // Generate quotes
                QUOTE_FREQUENCY
        );

        // ===================================================================
        // Wire up handlers to Disruptors
        // ===================================================================
        marketDataDisruptor.handleEventsWith(signalHandler);
        orderDisruptor.handleEventsWith(osmHandler);

        // ===================================================================
        // Start Disruptors
        // ===================================================================
        log.info("Starting RingBuffer-1 (Market Data)...");
        marketDataDisruptor.start();

        log.info("Starting RingBuffer-2 (Order Requests)...");
        orderDisruptor.start();

        // ===================================================================
        // Create Recombinor (Producer)
        // ===================================================================
        RingBuffer<BookUpdateEvent> marketDataRingBuffer = marketDataDisruptor.getRingBuffer();
        DisruptorRecombinor recombinor = new DisruptorRecombinor(marketDataRingBuffer, SYMBOL);

        // Allow threads to settle
        Thread.sleep(100);

        // ===================================================================
        // WARMUP PHASE
        // ===================================================================
        log.info("\n=== WARMUP PHASE ===");
        recombinor.warmup(WARMUP_EVENTS);

        // Wait for warmup to complete
        waitForProcessing("Signal", signalHandler::getMarketDataReceived, WARMUP_EVENTS, 5000);

        long warmupOrders = signalHandler.getOrdersGenerated();
        if (warmupOrders > 0) {
            waitForProcessing("OSM", osmHandler::getOrdersProcessed, warmupOrders, 5000);
        }

        log.info("Warmup complete:");
        log.info("  Market data processed: {}", signalHandler.getMarketDataReceived());
        log.info("  Orders generated: {}", signalHandler.getOrdersGenerated());
        log.info("  Orders processed: {}", osmHandler.getOrdersProcessed());

        // Small pause
        Thread.sleep(500);

        // ===================================================================
        // MEASUREMENT PHASE
        // ===================================================================
        log.info("\n=== MEASUREMENT PHASE ===");

        // Start measurement
        osmHandler.startMeasurement();

        long startTime = System.nanoTime();
        long initialMarketData = signalHandler.getMarketDataReceived();
        long initialOrders = signalHandler.getOrdersGenerated();

        // Generate measurement events
        recombinor.generateSyntheticData(MEASUREMENT_EVENTS, 0);

        // Wait for all market data to be processed by Signal
        waitForProcessing("Signal", signalHandler::getMarketDataReceived,
                initialMarketData + MEASUREMENT_EVENTS, 10000);

        // Wait for all orders to be processed by OSM
        long expectedOrders = signalHandler.getOrdersGenerated() - initialOrders;
        if (expectedOrders > 0) {
            waitForProcessing("OSM", osmHandler::getOrdersProcessed,
                    osmHandler.getOrdersProcessed() + expectedOrders, 10000);
        }

        long endTime = System.nanoTime();

        // Stop measurement
        osmHandler.stopMeasurement();

        // ===================================================================
        // Print Results
        // ===================================================================
        log.info("\n=== BENCHMARK RESULTS ===");
        long totalDurationNanos = endTime - startTime;
        double totalDurationMs = totalDurationNanos / 1_000_000.0;
        double overallThroughput = (MEASUREMENT_EVENTS * 1_000_000_000.0) / totalDurationNanos;

        log.info("Total end-to-end duration: {} ms", String.format("%.2f", totalDurationMs));
        log.info("Market data throughput: {} msgs/sec", String.format("%.0f", overallThroughput));

        log.info("\n");
        signalHandler.printStatistics();

        log.info("\n");
        osmHandler.printStatistics();

        // ===================================================================
        // Shutdown
        // ===================================================================
        log.info("\n=== Shutting down ===");
        orderDisruptor.shutdown();
        marketDataDisruptor.shutdown();

        log.info("Disruptors shutdown complete");
        log.info("=== Version 1 Complete ===");
    }

    /**
     * Wait for processing to complete
     */
    private static void waitForProcessing(String componentName, CounterSupplier counter,
                                          long expectedCount, long timeoutMs)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        while (counter.get() < expectedCount) {
            Thread.sleep(10);
            if (System.currentTimeMillis() - start > timeoutMs) {
                log.warn("{}: Timeout waiting for processing. Expected: {}, Actual: {}",
                        componentName, expectedCount, counter.get());
                break;
            }
        }
    }

    @FunctionalInterface
    interface CounterSupplier {
        long get();
    }
}

