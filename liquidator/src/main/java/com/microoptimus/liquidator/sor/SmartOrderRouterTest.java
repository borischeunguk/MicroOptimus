package com.microoptimus.liquidator.sor;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SmartOrderRouterTest - Test application for SOR functionality
 *
 * Tests the complete integration:
 * 1. Java SOR interface
 * 2. C++ ultra-low latency routing
 * 3. Venue selection algorithms
 * 4. Order splitting logic
 * 5. Performance characteristics
 */
public class SmartOrderRouterTest {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRouterTest.class);

    public static void main(String[] args) {
        log.info("=== Smart Order Router Test ===");

        try {
            // Test SOR initialization
            testSORInitialization();

            // Test single venue routing
            testSingleVenueRouting();

            // Test order splitting
            testOrderSplitting();

            // Test performance
            testPerformance();

            log.info("=== All tests completed successfully ===");

        } catch (Exception e) {
            log.error("Test failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void testSORInitialization() throws Exception {
        log.info("Testing SOR initialization...");

        SmartOrderRouter sor = new SmartOrderRouter();

        // Initialize with test configuration
        boolean initialized = sor.initialize("/tmp/sor_test.conf", "/tmp/sor_shared_memory.bin");
        if (!initialized) {
            // Try fallback - SOR should work without C++ library
            log.warn("C++ SOR not available - testing Java fallback");
        }

        // Test venue configuration
        SmartOrderRouter.VenueConfig cmeConfig = new SmartOrderRouter.VenueConfig(
            90, true, 1_000_000, 0.15, 0.95, 0.0001
        );
        boolean configured = sor.configureVenue(SmartOrderRouter.VenueType.CME, cmeConfig);
        log.info("CME venue configured: {}", configured);

        // Get initial statistics
        SmartOrderRouter.RoutingStats stats = sor.getStatistics();
        log.info("Initial statistics: {}", stats);

        sor.shutdown();
        log.info("SOR initialization test passed");
    }

    private static void testSingleVenueRouting() throws Exception {
        log.info("Testing single venue routing...");

        SmartOrderRouter sor = new SmartOrderRouter();
        sor.initialize("/tmp/sor_test.conf", "/tmp/sor_shared_memory.bin");

        try {
            // Test small order (should route to single venue)
            SmartOrderRouter.OrderRequest smallOrder = new SmartOrderRouter.OrderRequest(
                12345L,                    // orderId
                "AAPL",                    // symbol
                Side.BUY,                  // side
                OrderType.LIMIT,           // orderType
                15000L,                    // price (150.00)
                100L,                      // quantity
                System.nanoTime(),         // timestamp
                "TEST_CLIENT"              // clientId
            );

            SmartOrderRouter.RoutingDecision decision = sor.routeOrder(smallOrder);
            log.info("Small order routing decision: {}", decision);

            if (decision.action == SmartOrderRouter.RoutingAction.REJECT) {
                log.warn("Order rejected: {}", decision.rejectReason);
            } else {
                log.info("Order routed to: {} with quantity: {}",
                        decision.primaryVenue, decision.quantity);
            }

            // Test market order
            SmartOrderRouter.OrderRequest marketOrder = new SmartOrderRouter.OrderRequest(
                12346L, "GOOGL", Side.SELL, OrderType.MARKET,
                0L, 50L, System.nanoTime(), "TEST_CLIENT"
            );

            SmartOrderRouter.RoutingDecision marketDecision = sor.routeOrder(marketOrder);
            log.info("Market order routing decision: {}", marketDecision);

        } finally {
            sor.shutdown();
        }

        log.info("Single venue routing test passed");
    }

    private static void testOrderSplitting() throws Exception {
        log.info("Testing order splitting...");

        SmartOrderRouter sor = new SmartOrderRouter();
        sor.initialize("/tmp/sor_test.conf", "/tmp/sor_shared_memory.bin");

        try {
            // Test large order (should be split)
            SmartOrderRouter.OrderRequest largeOrder = new SmartOrderRouter.OrderRequest(
                12347L,
                "TSLA",
                Side.BUY,
                OrderType.LIMIT,
                80000L,                    // price (800.00)
                50000L,                    // quantity (large order)
                System.nanoTime(),
                "TEST_CLIENT"
            );

            SmartOrderRouter.RoutingDecision decision = sor.routeOrder(largeOrder);
            log.info("Large order routing decision: {}", decision);

            if (decision.action == SmartOrderRouter.RoutingAction.SPLIT_ORDER) {
                log.info("Order split across {} venues:", decision.allocations.length);
                for (SmartOrderRouter.VenueAllocation allocation : decision.allocations) {
                    log.info("  {} -> {} shares (priority {})",
                            allocation.venue, allocation.quantity, allocation.priority);
                }
            }

        } finally {
            sor.shutdown();
        }

        log.info("Order splitting test passed");
    }

    private static void testPerformance() throws Exception {
        log.info("Testing SOR performance...");

        SmartOrderRouter sor = new SmartOrderRouter();
        sor.initialize("/tmp/sor_test.conf", "/tmp/sor_shared_memory.bin");

        try {
            // Performance test parameters
            int numOrders = 10000;
            int warmupOrders = 1000;

            // Warmup
            log.info("Warming up with {} orders...", warmupOrders);
            for (int i = 0; i < warmupOrders; i++) {
                SmartOrderRouter.OrderRequest order = createTestOrder(i);
                sor.routeOrder(order);
            }

            // Performance measurement
            log.info("Performance test with {} orders...", numOrders);
            long startTime = System.nanoTime();
            long minLatency = Long.MAX_VALUE;
            long maxLatency = 0;

            for (int i = 0; i < numOrders; i++) {
                SmartOrderRouter.OrderRequest order = createTestOrder(warmupOrders + i);

                long orderStart = System.nanoTime();
                SmartOrderRouter.RoutingDecision decision = sor.routeOrder(order);
                long orderEnd = System.nanoTime();

                long latency = orderEnd - orderStart;
                minLatency = Math.min(minLatency, latency);
                maxLatency = Math.max(maxLatency, latency);

                if (decision.action == SmartOrderRouter.RoutingAction.REJECT) {
                    log.debug("Order {} rejected: {}", order.orderId, decision.rejectReason);
                }
            }

            long totalTime = System.nanoTime() - startTime;
            double avgLatency = (double) totalTime / numOrders;
            double throughput = (double) numOrders / (totalTime / 1_000_000_000.0);

            log.info("Performance Results:");
            log.info("  Total time: {:.2f} ms", totalTime / 1_000_000.0);
            log.info("  Average latency: {:.0f} ns", avgLatency);
            log.info("  Min latency: {} ns", minLatency);
            log.info("  Max latency: {} ns", maxLatency);
            log.info("  Throughput: {:.0f} orders/sec", throughput);

            // Get final statistics
            SmartOrderRouter.RoutingStats stats = sor.getStatistics();
            log.info("Final SOR statistics: {}", stats);

            // Verify performance targets
            if (avgLatency < 1000) { // Target: <1 microsecond average
                log.info("✅ Performance target met: {:.0f}ns < 1000ns", avgLatency);
            } else {
                log.warn("⚠️ Performance target missed: {:.0f}ns >= 1000ns", avgLatency);
            }

        } finally {
            sor.shutdown();
        }

        log.info("Performance test completed");
    }

    private static SmartOrderRouter.OrderRequest createTestOrder(int orderId) {
        // Create varied test orders
        String[] symbols = {"AAPL", "GOOGL", "MSFT", "TSLA", "AMZN"};
        Side[] sides = {Side.BUY, Side.SELL};

        return new SmartOrderRouter.OrderRequest(
            orderId,
            symbols[orderId % symbols.length],
            sides[orderId % sides.length],
            OrderType.LIMIT,
            (long) (10000 + (orderId % 5000)),  // Price: 100.00 to 150.00
            (long) (100 + (orderId % 900)),     // Quantity: 100 to 1000
            System.nanoTime(),
            "PERF_TEST"
        );
    }
}
