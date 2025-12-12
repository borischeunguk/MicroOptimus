package com.microoptimus.liquidator.sor;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SmartOrderRouterJavaFallback - Pure Java implementation for testing
 *
 * This version works without C++ dependencies and provides a fallback
 * implementation of the Smart Order Router for integration testing.
 */
public class SmartOrderRouterJavaFallback {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRouterJavaFallback.class);

    // Simple venue scoring
    private static class VenueScorer {
        public SmartOrderRouter.VenueType selectBestVenue(SmartOrderRouter.OrderRequest order) {
            // Simple logic: small orders to CME, large to multiple venues
            if (order.quantity < 1000) {
                return SmartOrderRouter.VenueType.CME;
            } else if (order.quantity < 10000) {
                return SmartOrderRouter.VenueType.NASDAQ;
            } else {
                return SmartOrderRouter.VenueType.NYSE;
            }
        }
    }

    private final VenueScorer venueScorer = new VenueScorer();
    private boolean initialized = false;

    // Statistics
    private long totalOrders = 0;
    private long internalRoutes = 0;
    private long externalRoutes = 0;
    private long rejectedOrders = 0;
    private long totalLatencyNanos = 0;

    public boolean initialize(String configPath, String sharedMemoryPath) {
        log.info("Initializing Java fallback SOR (no C++ dependencies)");
        initialized = true;
        return true;
    }

    public SmartOrderRouter.RoutingDecision routeOrder(SmartOrderRouter.OrderRequest request) {
        if (!initialized) {
            return SmartOrderRouter.RoutingDecision.rejected("Not initialized");
        }

        long startTime = System.nanoTime();
        totalOrders++;

        try {
            // Simple risk checks
            if (request.quantity <= 0 || request.quantity > 1_000_000) {
                rejectedOrders++;
                return SmartOrderRouter.RoutingDecision.rejected("Invalid quantity");
            }

            // Route based on order size
            SmartOrderRouter.VenueType venue = venueScorer.selectBestVenue(request);

            if (venue == SmartOrderRouter.VenueType.INTERNAL) {
                internalRoutes++;
            } else {
                externalRoutes++;
            }

            // Simulate routing decision
            SmartOrderRouter.RoutingDecision decision = new SmartOrderRouter.RoutingDecision(
                venue == SmartOrderRouter.VenueType.INTERNAL ?
                    SmartOrderRouter.RoutingAction.ROUTE_INTERNAL :
                    SmartOrderRouter.RoutingAction.ROUTE_EXTERNAL,
                venue,
                request.quantity,
                estimateLatency(venue)
            );

            return decision;

        } finally {
            long latency = System.nanoTime() - startTime;
            totalLatencyNanos += latency;
        }
    }

    private long estimateLatency(SmartOrderRouter.VenueType venue) {
        switch (venue) {
            case INTERNAL: return 200_000; // 200μs
            case CME: return 150_000; // 150μs
            case NASDAQ: return 200_000; // 200μs
            case NYSE: return 250_000; // 250μs
            default: return 300_000; // 300μs
        }
    }

    public SmartOrderRouter.RoutingStats getStatistics() {
        long avgLatency = totalOrders > 0 ? totalLatencyNanos / totalOrders : 0;

        return new SmartOrderRouter.RoutingStats(
            totalOrders,
            internalRoutes,
            externalRoutes,
            rejectedOrders,
            avgLatency,
            0, // max latency not tracked in fallback
            0  // min latency not tracked in fallback
        );
    }

    public void shutdown() {
        initialized = false;
        log.info("Java fallback SOR shutdown");
    }

    // Test method for integration
    public static void main(String[] args) {
        System.out.println("=== Java Fallback Smart Order Router Test ===");

        SmartOrderRouterJavaFallback sor = new SmartOrderRouterJavaFallback();

        // Initialize
        boolean initialized = sor.initialize("/tmp/test.conf", "/tmp/test.bin");
        System.out.println("Initialized: " + initialized);

        // Test orders
        SmartOrderRouter.OrderRequest[] testOrders = {
            new SmartOrderRouter.OrderRequest(1, "AAPL", Side.BUY, OrderType.LIMIT, 15000, 100, System.nanoTime(), "TEST"),
            new SmartOrderRouter.OrderRequest(2, "GOOGL", Side.SELL, OrderType.LIMIT, 280000, 500, System.nanoTime(), "TEST"),
            new SmartOrderRouter.OrderRequest(3, "TSLA", Side.BUY, OrderType.LIMIT, 80000, 2000, System.nanoTime(), "TEST"),
        };

        // Performance test
        long startTime = System.nanoTime();

        for (SmartOrderRouter.OrderRequest order : testOrders) {
            SmartOrderRouter.RoutingDecision decision = sor.routeOrder(order);
            System.out.printf("Order %d -> %s (%s) - Estimated fill time: %dμs%n",
                order.orderId, decision.primaryVenue, decision.action,
                decision.estimatedFillTimeNanos / 1000);
        }

        long totalTime = System.nanoTime() - startTime;

        // Statistics
        SmartOrderRouter.RoutingStats stats = sor.getStatistics();
        System.out.println("\nStatistics: " + stats);
        System.out.printf("Total test time: %.2f ms%n", totalTime / 1_000_000.0);
        System.out.printf("Average per order: %.0f ns%n", (double)totalTime / testOrders.length);

        sor.shutdown();
        System.out.println("=== Test completed successfully ===");
    }
}
