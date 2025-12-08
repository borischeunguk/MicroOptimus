package com.microoptimus.osm.disruptor;

import com.lmax.disruptor.EventHandler;
import com.microoptimus.common.events.disruptor.OrderRequestEvent;
import com.microoptimus.osm.OrderBook;
import com.microoptimus.osm.Order;
import com.microoptimus.common.types.OrderType;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DisruptorOSMHandler - Order & Matching Engine Handler
 *
 * Receives OrderRequestEvents from Signal module via Disruptor RingBuffer-2
 *
 * Responsibilities:
 * 1. Receive order requests from market making strategy
 * 2. Process orders through OrderBook (add, cancel, modify)
 * 3. Generate executions when orders match
 * 4. Maintain order book state
 *
 * Measures latency from order creation to order processing.
 */
public class DisruptorOSMHandler implements EventHandler<OrderRequestEvent> {

    private static final Logger log = LoggerFactory.getLogger(DisruptorOSMHandler.class);

    // OrderBook for matching
    private final OrderBook orderBook;

    // Metrics
    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersMatched = new AtomicLong(0);
    private final AtomicLong executionsGenerated = new AtomicLong(0);
    private final Histogram latencyHistogram;
    private boolean measurementPhase = false;
    private long measurementStartTime;
    private long measurementEndTime;

    // Configuration
    private final boolean logOrders;
    private final int logEveryN;

    public DisruptorOSMHandler(String symbol, boolean logOrders, int logEveryN) {
        this.orderBook = new OrderBook(symbol);
        this.logOrders = logOrders;
        this.logEveryN = logEveryN;

        // Create histogram with 3 significant digits, max value 1 hour in nanos
        this.latencyHistogram = new Histogram(3_600_000_000_000L, 3);
    }

    public DisruptorOSMHandler(String symbol) {
        this(symbol, false, 10000);
    }

    /**
     * Start measurement phase (after warmup)
     */
    public void startMeasurement() {
        measurementPhase = true;
        measurementStartTime = System.nanoTime();
        latencyHistogram.reset();
        ordersProcessed.set(0);
        ordersAccepted.set(0);
        ordersMatched.set(0);
        executionsGenerated.set(0);
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
    public void onEvent(OrderRequestEvent event, long sequence, boolean endOfBatch) throws Exception {
        // Record receipt timestamp
        long receiveTime = System.nanoTime();

        // Calculate latency (receive time - event timestamp)
        long latencyNanos = receiveTime - event.getTimestamp();

        // Process order through OrderBook
        Order order = processOrder(event);

        long processed = ordersProcessed.incrementAndGet();

        // Track order acceptance
        if (order != null && order.getState() != Order.OrderState.REJECTED) {
            ordersAccepted.incrementAndGet();

            // Track executions
            if (order.getExecutedSize() > 0) {
                executionsGenerated.incrementAndGet();
            }

            // Track fully matched orders
            if (order.isFilled()) {
                ordersMatched.incrementAndGet();
            }
        }

        // Record metrics during measurement phase
        if (measurementPhase && latencyNanos > 0 && latencyNanos < 3_600_000_000_000L) {
            latencyHistogram.recordValue(latencyNanos);
        }

        // Optional logging
        if (logOrders && (processed % logEveryN == 0)) {
            log.info("Processed order #{}: {} | Latency: {} ns | Book: {} orders | EndOfBatch: {}",
                    processed, event, latencyNanos, orderBook.getOrderCount(), endOfBatch);
        }
    }

    /**
     * Process order request through OrderBook
     */
    private Order processOrder(OrderRequestEvent event) {
        Order order = null;

        try {
            if (event.getOrderType() == OrderType.LIMIT) {
                // Add limit order
                order = orderBook.addLimitOrder(
                    event.getOrderId(),
                    event.getClientId(),
                    event.getSide(),
                    event.getPrice(),
                    event.getQuantity(),
                    event.getTimeInForce()
                );
            } else if (event.getOrderType() == OrderType.MARKET) {
                // Add market order
                order = orderBook.addMarketOrder(
                    event.getOrderId(),
                    event.getClientId(),
                    event.getSide(),
                    event.getQuantity()
                );
            }
        } catch (Exception e) {
            log.error("Error processing order: {}", event, e);
        }

        return order;
    }

    /**
     * Get OrderBook for inspection/testing
     */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * Get processing statistics
     */
    public long getOrdersProcessed() {
        return ordersProcessed.get();
    }

    public long getOrdersAccepted() {
        return ordersAccepted.get();
    }

    public long getOrdersMatched() {
        return ordersMatched.get();
    }

    public long getExecutionsGenerated() {
        return executionsGenerated.get();
    }

    /**
     * Print statistics
     */
    public void printStatistics() {
        long totalOrders = ordersProcessed.get();
        long durationNanos = measurementEndTime - measurementStartTime;
        double durationMs = durationNanos / 1_000_000.0;
        double throughput = (totalOrders * 1_000_000_000.0) / durationNanos;

        log.info("=== OSM Handler Statistics ===");
        log.info("Total orders processed: {}", totalOrders);
        log.info("Orders accepted: {}", ordersAccepted.get());
        log.info("Orders matched (filled): {}", ordersMatched.get());
        log.info("Executions generated: {}", executionsGenerated.get());
        log.info("Duration: {} ms", String.format("%.2f", durationMs));
        log.info("Throughput: {} orders/sec", String.format("%.0f", throughput));

        if (latencyHistogram.getTotalCount() > 0) {
            log.info("=== Latency Statistics (Signal → OSM) ===");
            log.info("  Min:    {} ns", latencyHistogram.getMinValue());
            log.info("  Mean:   {} ns", String.format("%.0f", latencyHistogram.getMean()));
            log.info("  Median: {} ns", latencyHistogram.getValueAtPercentile(50.0));
            log.info("  P90:    {} ns", latencyHistogram.getValueAtPercentile(90.0));
            log.info("  P95:    {} ns", latencyHistogram.getValueAtPercentile(95.0));
            log.info("  P99:    {} ns", latencyHistogram.getValueAtPercentile(99.0));
            log.info("  P99.9:  {} ns", latencyHistogram.getValueAtPercentile(99.9));
            log.info("  P99.99: {} ns", latencyHistogram.getValueAtPercentile(99.99));
            log.info("  Max:    {} ns", latencyHistogram.getMaxValue());
        }

        log.info("=== OrderBook State ===");
        log.info("  Symbol: {}", orderBook.getSymbol());
        log.info("  Total Orders: {}", orderBook.getOrderCount());
        log.info("  Bid Levels: {}", orderBook.getBidLevels());
        log.info("  Ask Levels: {}", orderBook.getAskLevels());
        log.info("  Best Bid: {} @ {}", orderBook.getBestBidSize(), orderBook.getBestBidPrice());
        log.info("  Best Ask: {} @ {}", orderBook.getBestAskSize(), orderBook.getBestAskPrice());
        log.info("  Book State: {}", orderBook.getState());
        log.info("  Last Executed Price: {}", orderBook.getLastExecutedPrice());
    }

    // Getters for testing
    public Histogram getLatencyHistogram() {
        return latencyHistogram;
    }
}

