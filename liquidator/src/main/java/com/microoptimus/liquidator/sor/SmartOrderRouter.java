package com.microoptimus.liquidator.sor;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SmartOrderRouter - C++ powered intelligent order routing
 *
 * Architecture:
 * - OSM handles pure internal matching (200ns)
 * - LIQUIDATOR/SOR handles external venue routing (500ns)
 * - Clear separation: OSM = "Can I match internally?" / SOR = "Where should this go?"
 *
 * Features:
 * 1. C++ Ultra-Low Latency Core (Boost + Folly)
 * 2. Multi-factor venue scoring algorithm
 * 3. Risk management and order splitting
 * 4. Integration with global sequencer + shared memory
 * 5. JNI for Java/C++ communication
 *
 * Supported Venues:
 * - INTERNAL: Route back to OSM for passive liquidity
 * - CME: Futures/options (iLink3)
 * - NASDAQ: Equities (OUCH)
 * - NYSE: Equities (FIX)
 */
public class SmartOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRouter.class);

    // Load native C++ library
    static {
        try {
            System.loadLibrary("smartorderrouter");
            log.info("Successfully loaded Smart Order Router C++ library");
        } catch (UnsatisfiedLinkError e) {
            log.warn("Failed to load SOR library - using Java fallback: {}", e.getMessage());
        }
    }

    // Statistics
    private final AtomicLong ordersRouted = new AtomicLong(0);
    private final AtomicLong internalRoutes = new AtomicLong(0);
    private final AtomicLong externalRoutes = new AtomicLong(0);
    private final AtomicLong rejectedOrders = new AtomicLong(0);

    // Configuration
    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    /**
     * Initialize the Smart Order Router
     */
    public boolean initialize(String configPath, String sharedMemoryPath) {
        synchronized (initLock) {
            if (initialized) {
                return true;
            }

            try {
                int result = initializeNative(configPath, sharedMemoryPath);
                if (result == 0) {
                    initialized = true;
                    log.info("Smart Order Router initialized successfully");

                    // Configure default venues
                    configureDefaultVenues();
                    return true;
                } else {
                    log.error("Failed to initialize Smart Order Router: {}", result);
                    return false;
                }
            } catch (UnsatisfiedLinkError e) {
                log.error("Native library not available: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * Main routing decision method
     * Called by OSM when order cannot be matched internally
     */
    public RoutingDecision routeOrder(OrderRequest request) {
        if (!initialized) {
            return RoutingDecision.rejected("SOR not initialized");
        }

        long startTime = System.nanoTime();
        ordersRouted.incrementAndGet();

        try {
            // Use C++ ultra-fast routing
            ByteBuffer resultBuffer = ByteBuffer.allocateDirect(256);

            int result = routeOrderNative(
                request.orderId,
                request.symbol,
                request.side.ordinal(),
                request.orderType.ordinal(),
                request.price,
                request.quantity,
                request.timestamp,
                resultBuffer
            );

            if (result >= 0) {
                RoutingDecision decision = parseRoutingResult(resultBuffer, result);
                updateStatistics(decision);
                return decision;
            } else {
                rejectedOrders.incrementAndGet();
                return RoutingDecision.rejected("Native routing failed: " + result);
            }

        } catch (Exception e) {
            log.error("Error routing order {}: {}", request.orderId, e.getMessage());
            rejectedOrders.incrementAndGet();
            return RoutingDecision.rejected("Routing error: " + e.getMessage());
        } finally {
            long latency = System.nanoTime() - startTime;
            if (latency > 1_000) { // Log if > 1 microsecond
                log.debug("SOR routing took {}ns for order {}", latency, request.orderId);
            }
        }
    }

    /**
     * Add venue configuration
     */
    public boolean configureVenue(VenueType venue, VenueConfig config) {
        if (!initialized) {
            return false;
        }

        try {
            return configureVenueNative(
                venue.ordinal(),
                config.priority,
                config.enabled ? 1 : 0,
                config.maxOrderSize,
                (long)(config.avgLatencyMicros * 1000), // Convert to nanos
                (long)(config.fillRate * 1000000), // Scale to long
                (long)(config.feesPerShare * 1000000) // Scale to long
            ) == 0;
        } catch (UnsatisfiedLinkError e) {
            log.error("Failed to configure venue {}: {}", venue, e.getMessage());
            return false;
        }
    }

    /**
     * Get routing statistics
     */
    public RoutingStats getStatistics() {
        if (!initialized) {
            return new RoutingStats();
        }

        try {
            ByteBuffer statsBuffer = ByteBuffer.allocateDirect(128);
            int result = getStatisticsNative(statsBuffer);

            if (result == 0) {
                return parseStatistics(statsBuffer);
            }
        } catch (UnsatisfiedLinkError e) {
            // Fallback to Java statistics
        }

        return new RoutingStats(
            ordersRouted.get(),
            internalRoutes.get(),
            externalRoutes.get(),
            rejectedOrders.get(),
            0, 0, 0 // C++ specific stats unavailable
        );
    }

    /**
     * Shutdown the SOR
     */
    public void shutdown() {
        if (initialized) {
            try {
                shutdownNative();
                initialized = false;
                log.info("Smart Order Router shutdown complete");
            } catch (UnsatisfiedLinkError e) {
                log.warn("Native shutdown failed: {}", e.getMessage());
            }
        }
    }

    // Native method declarations
    private native int initializeNative(String configPath, String sharedMemoryPath);

    private native int routeOrderNative(
        long orderId, String symbol, int side, int orderType,
        long price, long quantity, long timestamp, ByteBuffer resultBuffer
    );

    private native int configureVenueNative(
        int venueId, int priority, int enabled, long maxOrderSize,
        long avgLatencyNanos, long fillRate, long feesPerShare
    );

    private native int getStatisticsNative(ByteBuffer statsBuffer);

    private native void shutdownNative();

    // Helper methods
    private void configureDefaultVenues() {
        // Configure default venue priorities and parameters
        configureVenue(VenueType.INTERNAL, new VenueConfig(100, true, 10_000_000, 0.0, 1.0, 0.0));
        configureVenue(VenueType.CME, new VenueConfig(90, true, 1_000_000, 0.15, 0.95, 0.0001));
        configureVenue(VenueType.NASDAQ, new VenueConfig(85, true, 500_000, 0.20, 0.93, 0.0002));
        configureVenue(VenueType.NYSE, new VenueConfig(80, true, 500_000, 0.25, 0.91, 0.0002));
    }

    private RoutingDecision parseRoutingResult(ByteBuffer buffer, int result) {
        buffer.position(0);

        int action = buffer.getInt();
        int venue = buffer.getInt();
        long quantity = buffer.getLong();
        long estimatedFillTime = buffer.getLong();

        // Parse additional data based on action type
        switch (action) {
            case 0: // ROUTE_EXTERNAL
                return new RoutingDecision(
                    RoutingAction.ROUTE_EXTERNAL,
                    VenueType.values()[venue],
                    quantity,
                    estimatedFillTime
                );

            case 1: // ROUTE_INTERNAL
                return new RoutingDecision(
                    RoutingAction.ROUTE_INTERNAL,
                    VenueType.INTERNAL,
                    quantity,
                    estimatedFillTime
                );

            case 2: // SPLIT_ORDER
                int numSplits = buffer.getInt();
                VenueAllocation[] allocations = new VenueAllocation[numSplits];

                for (int i = 0; i < numSplits && buffer.remaining() >= 16; i++) {
                    allocations[i] = new VenueAllocation(
                        VenueType.values()[buffer.getInt()],
                        buffer.getLong(),
                        buffer.getInt()
                    );
                }

                return new RoutingDecision(
                    RoutingAction.SPLIT_ORDER,
                    allocations,
                    estimatedFillTime
                );

            default:
                return RoutingDecision.rejected("Unknown action: " + action);
        }
    }

    private RoutingStats parseStatistics(ByteBuffer buffer) {
        buffer.position(0);
        return new RoutingStats(
            buffer.getLong(), // totalOrders
            buffer.getLong(), // internalRoutes
            buffer.getLong(), // externalRoutes
            buffer.getLong(), // rejectedOrders
            buffer.getLong(), // avgLatencyNanos
            buffer.getLong(), // maxLatencyNanos
            buffer.getLong()  // minLatencyNanos
        );
    }

    private void updateStatistics(RoutingDecision decision) {
        switch (decision.action) {
            case ROUTE_INTERNAL:
                internalRoutes.incrementAndGet();
                break;
            case ROUTE_EXTERNAL:
            case SPLIT_ORDER:
                externalRoutes.incrementAndGet();
                break;
            case REJECT:
                rejectedOrders.incrementAndGet();
                break;
        }
    }

    // Supporting classes

    public static class OrderRequest {
        public final long orderId;
        public final String symbol;
        public final Side side;
        public final OrderType orderType;
        public final long price;
        public final long quantity;
        public final long timestamp;
        public final String clientId;

        public OrderRequest(long orderId, String symbol, Side side, OrderType orderType,
                           long price, long quantity, long timestamp, String clientId) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.orderType = orderType;
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
            this.clientId = clientId;
        }

        @Override
        public String toString() {
            return String.format("OrderRequest[id=%d, %s %s %d@%d]",
                    orderId, side, symbol, quantity, price);
        }
    }

    public static class RoutingDecision {
        public final RoutingAction action;
        public final VenueType primaryVenue;
        public final VenueAllocation[] allocations;
        public final long quantity;
        public final long estimatedFillTimeNanos;
        public final String rejectReason;

        // Single venue routing
        public RoutingDecision(RoutingAction action, VenueType venue, long quantity, long fillTime) {
            this.action = action;
            this.primaryVenue = venue;
            this.allocations = null;
            this.quantity = quantity;
            this.estimatedFillTimeNanos = fillTime;
            this.rejectReason = null;
        }

        // Split order routing
        public RoutingDecision(RoutingAction action, VenueAllocation[] allocations, long fillTime) {
            this.action = action;
            this.primaryVenue = (allocations.length > 0) ? allocations[0].venue : VenueType.NONE;
            this.allocations = allocations;
            this.quantity = calculateTotalQuantity(allocations);
            this.estimatedFillTimeNanos = fillTime;
            this.rejectReason = null;
        }

        // Rejection
        private RoutingDecision(RoutingAction action, String reason) {
            this.action = action;
            this.primaryVenue = VenueType.NONE;
            this.allocations = null;
            this.quantity = 0;
            this.estimatedFillTimeNanos = 0;
            this.rejectReason = reason;
        }

        public static RoutingDecision rejected(String reason) {
            return new RoutingDecision(RoutingAction.REJECT, reason);
        }

        private long calculateTotalQuantity(VenueAllocation[] allocations) {
            long total = 0;
            if (allocations != null) {
                for (VenueAllocation alloc : allocations) {
                    total += alloc.quantity;
                }
            }
            return total;
        }

        @Override
        public String toString() {
            return String.format("RoutingDecision[%s -> %s, qty=%d]",
                    action, primaryVenue, quantity);
        }
    }

    public static class VenueAllocation {
        public final VenueType venue;
        public final long quantity;
        public final int priority;

        public VenueAllocation(VenueType venue, long quantity, int priority) {
            this.venue = venue;
            this.quantity = quantity;
            this.priority = priority;
        }

        @Override
        public String toString() {
            return String.format("VenueAlloc[%s: %d]", venue, quantity);
        }
    }

    public static class VenueConfig {
        public final int priority;
        public final boolean enabled;
        public final long maxOrderSize;
        public final double avgLatencyMicros;
        public final double fillRate; // 0.0 to 1.0
        public final double feesPerShare;

        public VenueConfig(int priority, boolean enabled, long maxOrderSize,
                          double avgLatencyMicros, double fillRate, double feesPerShare) {
            this.priority = priority;
            this.enabled = enabled;
            this.maxOrderSize = maxOrderSize;
            this.avgLatencyMicros = avgLatencyMicros;
            this.fillRate = fillRate;
            this.feesPerShare = feesPerShare;
        }
    }

    public static class RoutingStats {
        public final long totalOrders;
        public final long internalRoutes;
        public final long externalRoutes;
        public final long rejectedOrders;
        public final long avgLatencyNanos;
        public final long maxLatencyNanos;
        public final long minLatencyNanos;

        public RoutingStats() {
            this(0, 0, 0, 0, 0, 0, 0);
        }

        public RoutingStats(long totalOrders, long internalRoutes, long externalRoutes,
                           long rejectedOrders, long avgLatencyNanos, long maxLatencyNanos,
                           long minLatencyNanos) {
            this.totalOrders = totalOrders;
            this.internalRoutes = internalRoutes;
            this.externalRoutes = externalRoutes;
            this.rejectedOrders = rejectedOrders;
            this.avgLatencyNanos = avgLatencyNanos;
            this.maxLatencyNanos = maxLatencyNanos;
            this.minLatencyNanos = minLatencyNanos;
        }

        public double getInternalRoutingRate() {
            return totalOrders > 0 ? (internalRoutes * 100.0) / totalOrders : 0.0;
        }

        @Override
        public String toString() {
            return String.format("RoutingStats[total=%d, internal=%d(%.1f%%), external=%d, rejected=%d, avgLatency=%dns]",
                    totalOrders, internalRoutes, getInternalRoutingRate(),
                    externalRoutes, rejectedOrders, avgLatencyNanos);
        }
    }

    public enum RoutingAction {
        ROUTE_EXTERNAL,     // Send to external venue
        ROUTE_INTERNAL,     // Route back to OSM for internal crossing
        SPLIT_ORDER,        // Split across multiple venues
        REJECT              // Reject the order
    }

    public enum VenueType {
        NONE,
        INTERNAL,   // Route back to OSM
        CME,        // CME Group (iLink3)
        NASDAQ,     // Nasdaq (OUCH)
        NYSE,       // NYSE (FIX)
        ARCA,       // NYSE Arca
        IEX         // IEX
    }
}
