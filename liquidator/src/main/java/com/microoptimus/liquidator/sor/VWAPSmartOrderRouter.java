package com.microoptimus.liquidator.sor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * VWAPSmartOrderRouter - Enhanced SOR with VWAP scenario support
 *
 * Implements the exact VWAP scenario requirements:
 * - Multi-factor venue scoring (price + liquidity + latency + fees + fill rate)
 * - Smart allocation across internal and external venues
 * - Sub-microsecond routing decisions
 * - Internal venue prioritization (5μs latency, zero fees)
 */
public class VWAPSmartOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(VWAPSmartOrderRouter.class);

    // Load native library
    static {
        try {
            System.loadLibrary("smart_order_router");
            log.info("Loaded native SOR library successfully");
        } catch (UnsatisfiedLinkError e) {
            log.error("Failed to load native SOR library: {}", e.getMessage());
        }
    }

    // Venue types (matching C++ enum)
    public enum VenueType {
        NONE(0), INTERNAL(1), CME(2), NASDAQ(3), NYSE(4), ARCA(5), IEX(6), BATS(7);

        private final int value;
        VenueType(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    // Order side
    public enum Side {
        BUY(0), SELL(1);

        private final int value;
        Side(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    // Routing actions
    public enum RoutingAction {
        ROUTE_EXTERNAL(0), ROUTE_INTERNAL(1), SPLIT_ORDER(2), REJECT(3);

        private final int value;
        RoutingAction(int value) { this.value = value; }
        public int getValue() { return value; }

        public static RoutingAction fromValue(int value) {
            for (RoutingAction action : values()) {
                if (action.value == value) return action;
            }
            return REJECT;
        }
    }

    // VWAP slice request
    public static class VWAPSliceRequest {
        public final long sliceId;
        public final long totalOrderId;
        public final String symbol;
        public final Side side;
        public final long sliceQuantity;
        public final long limitPrice;        // Scaled by 1M for precision
        public final long maxLatencyNanos;
        public final int urgencyLevel;       // 1=urgent, 5=passive

        public VWAPSliceRequest(long sliceId, long totalOrderId, String symbol, Side side,
                               long sliceQuantity, double limitPrice, long maxLatencyNanos, int urgencyLevel) {
            this.sliceId = sliceId;
            this.totalOrderId = totalOrderId;
            this.symbol = symbol;
            this.side = side;
            this.sliceQuantity = sliceQuantity;
            this.limitPrice = (long)(limitPrice * 1000000); // Scale price
            this.maxLatencyNanos = maxLatencyNanos;
            this.urgencyLevel = urgencyLevel;
        }

        @Override
        public String toString() {
            return String.format("VWAPSlice[id=%d, %s %d@%.4f, urgency=%d]",
                    sliceId, side, sliceQuantity, limitPrice / 1000000.0, urgencyLevel);
        }
    }

    // Venue allocation result
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
            return String.format("%s: %d shares (priority %d)", venue, quantity, priority);
        }
    }

    // VWAP routing result
    public static class VWAPRoutingResult {
        public final RoutingAction action;
        public final VenueType primaryVenue;
        public final long totalQuantity;
        public final long estimatedFillTimeNanos;
        public final VenueAllocation[] allocations;
        public final String rejectReason;

        public VWAPRoutingResult(RoutingAction action, VenueType primaryVenue, long totalQuantity,
                                long estimatedFillTimeNanos, VenueAllocation[] allocations, String rejectReason) {
            this.action = action;
            this.primaryVenue = primaryVenue;
            this.totalQuantity = totalQuantity;
            this.estimatedFillTimeNanos = estimatedFillTimeNanos;
            this.allocations = allocations != null ? allocations : new VenueAllocation[0];
            this.rejectReason = rejectReason;
        }

        public boolean isSuccessful() {
            return action != RoutingAction.REJECT;
        }

        public long getEstimatedFillTimeMicros() {
            return estimatedFillTimeNanos / 1000;
        }

        @Override
        public String toString() {
            if (action == RoutingAction.REJECT) {
                return String.format("REJECTED: %s", rejectReason);
            } else if (action == RoutingAction.SPLIT_ORDER) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("SPLIT ORDER (%d allocations, ~%dμs fill time):",
                        allocations.length, getEstimatedFillTimeMicros()));
                for (VenueAllocation alloc : allocations) {
                    sb.append("\n  ").append(alloc);
                }
                return sb.toString();
            } else {
                return String.format("SINGLE VENUE: %s, %d shares, ~%dμs fill time",
                        primaryVenue, totalQuantity, getEstimatedFillTimeMicros());
            }
        }
    }

    private boolean initialized = false;
    private final ByteBuffer resultBuffer;

    public VWAPSmartOrderRouter() {
        // Allocate direct buffer for JNI communication (64 longs)
        this.resultBuffer = ByteBuffer.allocateDirect(512);
        this.resultBuffer.order(ByteOrder.nativeOrder());
    }

    /**
     * Initialize the enhanced SOR with VWAP support
     */
    public boolean initialize(String configPath, String sharedMemoryPath) {
        try {
            int result = initializeNative(configPath, sharedMemoryPath);
            this.initialized = (result == 0);

            if (initialized) {
                log.info("VWAP SOR initialized successfully");
            } else {
                log.error("Failed to initialize VWAP SOR, result: {}", result);
            }

            return initialized;
        } catch (UnsatisfiedLinkError e) {
            log.error("Native SOR library not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Route VWAP slice using enhanced multi-factor scoring - implements your exact scenario
     */
    public VWAPRoutingResult routeVWAPSlice(VWAPSliceRequest slice) {
        if (!initialized) {
            return new VWAPRoutingResult(RoutingAction.REJECT, VenueType.NONE, 0, 0, null, "SOR not initialized");
        }

        try {
            resultBuffer.clear();

            int result = routeVWAPSliceNative(
                slice.sliceId,
                slice.totalOrderId,
                slice.symbol,
                slice.side.getValue(),
                slice.sliceQuantity,
                slice.limitPrice,
                slice.maxLatencyNanos,
                slice.urgencyLevel,
                resultBuffer
            );

            if (result != 0) {
                return new VWAPRoutingResult(RoutingAction.REJECT, VenueType.NONE, 0, 0, null, "Native routing failed");
            }

            return parseRoutingResult();

        } catch (Exception e) {
            log.error("Error routing VWAP slice: {}", e.getMessage(), e);
            return new VWAPRoutingResult(RoutingAction.REJECT, VenueType.NONE, 0, 0, null, "Routing error: " + e.getMessage());
        }
    }

    private VWAPRoutingResult parseRoutingResult() {
        resultBuffer.rewind();

        int actionValue = resultBuffer.getInt();
        int primaryVenueValue = resultBuffer.getInt();
        long totalQuantity = resultBuffer.getLong();
        long estimatedFillTime = resultBuffer.getLong();

        RoutingAction action = RoutingAction.fromValue(actionValue);
        VenueType primaryVenue = VenueType.values()[primaryVenueValue];

        VenueAllocation[] allocations = null;
        if (action == RoutingAction.SPLIT_ORDER) {
            int numAllocations = resultBuffer.getInt();
            allocations = new VenueAllocation[numAllocations];

            for (int i = 0; i < numAllocations && i < 4; i++) {
                int venueValue = resultBuffer.getInt();
                long quantity = resultBuffer.getLong();
                int priority = resultBuffer.getInt();

                VenueType venue = VenueType.values()[venueValue];
                allocations[i] = new VenueAllocation(venue, quantity, priority);
            }
        }

        return new VWAPRoutingResult(action, primaryVenue, totalQuantity, estimatedFillTime, allocations, null);
    }

    /**
     * Shutdown the SOR
     */
    public void shutdown() {
        if (initialized) {
            shutdownNative();
            initialized = false;
            log.info("VWAP SOR shutdown complete");
        }
    }

    // Native method declarations
    private native int initializeNative(String configPath, String sharedMemoryPath);
    private native int routeVWAPSliceNative(long sliceId, long totalOrderId, String symbol, int side,
                                           long sliceQuantity, long limitPrice, long maxLatencyNanos,
                                           int urgencyLevel, ByteBuffer resultBuffer);
    private native void shutdownNative();
}
