package com.microoptimus.common.model;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;

/**
 * Unified Order data structure used across all modules
 * - Signal (Market Making)
 * - OSM (Unified Matching Engine)
 * - Liquidator (Smart Order Router)
 * - Global Sequencer / Shared Memory
 *
 * Designed for:
 * - Zero-copy serialization
 * - Cache-friendly layout
 * - Lock-free operations
 * - GC-free object pooling
 */
public class UnifiedOrder {

    // === Core Identity ===
    public long orderId;                    // Unique order identifier
    public long parentOrderId;             // For child orders (order splitting)
    public long clientOrderId;             // Client-provided ID
    public long globalSequenceId;          // Global sequencer position

    // === Order Details ===
    public String symbol;                   // Instrument symbol (e.g., "ES.CME")
    public Side side;                      // BUY or SELL
    public OrderType orderType;            // MARKET, LIMIT, IOC, FOK

    // === Price and Quantity ===
    public long price;                     // Price in ticks (scaled for precision)
    public long quantity;                  // Original quantity
    public long filledQuantity;           // Already filled quantity
    public long remainingQuantity;        // Remaining to fill

    // === Timestamps (nanoseconds) ===
    public long creationTime;             // Order creation timestamp
    public long receivedTime;             // Time received by system
    public long lastUpdateTime;           // Last modification timestamp

    // === Order State ===
    public OrderState state;              // NEW, ACCEPTED, PARTIALLY_FILLED, FILLED, REJECTED
    public OrderSource source;            // INTERNAL_MM, EXTERNAL_CLIENT, DARK_POOL, SOR

    // === Routing Information ===
    public VenueType targetVenue;         // CME, NASDAQ, NYSE, INTERNAL
    public int priority;                  // Matching priority (lower = higher priority)

    // === Client Information ===
    public String clientId;               // Client identifier
    public String traderId;               // Trader identifier
    public String account;                // Account identifier

    // === Risk and Limits ===
    public long maxFloor;                 // Maximum quantity to display (iceberg)
    public long minQuantity;              // Minimum fill quantity
    public long timeInForce;              // Time in force (DAY, IOC, FOK, etc.)

    // === Performance Metadata ===
    public volatile long version;          // Version for optimistic locking
    public long cacheLinesPadding1;       // CPU cache line padding
    public long cacheLinesPadding2;       // CPU cache line padding

    // Default constructor
    public UnifiedOrder() {
        this.state = OrderState.NEW;
        this.source = OrderSource.UNKNOWN;
        this.targetVenue = VenueType.NONE;
        this.priority = Integer.MAX_VALUE;
        this.creationTime = System.nanoTime();
        this.version = 1;
    }

    // Full constructor
    public UnifiedOrder(long orderId, String symbol, Side side, OrderType orderType,
                       long price, long quantity, String clientId) {
        this();
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.clientId = clientId;
    }

    // === Utility Methods ===

    /**
     * Check if order is fully filled
     */
    public boolean isFullyFilled() {
        return remainingQuantity <= 0 || state == OrderState.FILLED;
    }

    /**
     * Check if order is active (can be matched)
     */
    public boolean isActive() {
        return state == OrderState.ACCEPTED && remainingQuantity > 0;
    }

    /**
     * Update filled quantity and state
     */
    public void addFill(long fillQuantity) {
        this.filledQuantity += fillQuantity;
        this.remainingQuantity = Math.max(0, this.quantity - this.filledQuantity);
        this.lastUpdateTime = System.nanoTime();
        this.version++;

        if (remainingQuantity <= 0) {
            this.state = OrderState.FILLED;
        } else if (filledQuantity > 0) {
            this.state = OrderState.PARTIALLY_FILLED;
        }
    }

    /**
     * Create a copy for routing (e.g., to external venues)
     */
    public UnifiedOrder createChildOrder(long childOrderId, long childQuantity, VenueType venue) {
        UnifiedOrder child = new UnifiedOrder();

        // Copy parent attributes
        child.orderId = childOrderId;
        child.parentOrderId = this.orderId;
        child.clientOrderId = this.clientOrderId;
        child.symbol = this.symbol;
        child.side = this.side;
        child.orderType = this.orderType;
        child.price = this.price;
        child.quantity = childQuantity;
        child.remainingQuantity = childQuantity;
        child.clientId = this.clientId;
        child.traderId = this.traderId;
        child.account = this.account;
        child.targetVenue = venue;
        child.source = OrderSource.SOR;

        return child;
    }

    /**
     * Reset order for object pooling (GC-free)
     */
    public void reset() {
        this.orderId = 0;
        this.parentOrderId = 0;
        this.clientOrderId = 0;
        this.globalSequenceId = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.quantity = 0;
        this.filledQuantity = 0;
        this.remainingQuantity = 0;
        this.creationTime = 0;
        this.receivedTime = 0;
        this.lastUpdateTime = 0;
        this.state = OrderState.NEW;
        this.source = OrderSource.UNKNOWN;
        this.targetVenue = VenueType.NONE;
        this.priority = Integer.MAX_VALUE;
        this.clientId = null;
        this.traderId = null;
        this.account = null;
        this.maxFloor = 0;
        this.minQuantity = 0;
        this.timeInForce = 0;
        this.version = 1;
    }

    @Override
    public String toString() {
        return String.format("UnifiedOrder[id=%d, %s %s %d@%d, filled=%d, remaining=%d, state=%s, venue=%s]",
                orderId, side, symbol, quantity, price, filledQuantity, remainingQuantity, state, targetVenue);
    }

    // === Enums ===

    public enum OrderState {
        NEW,                // Just created
        ACCEPTED,           // Accepted by matching engine
        PARTIALLY_FILLED,   // Partially executed
        FILLED,             // Fully executed
        REJECTED,           // Rejected by system
        CANCELLED,          // Cancelled by client
        EXPIRED             // Time expired
    }

    public enum OrderSource {
        UNKNOWN,            // Unknown source
        INTERNAL_MM,        // Internal market making (Signal module)
        EXTERNAL_CLIENT,    // External client order
        DARK_POOL,          // Dark pool order
        SOR,                // Smart order router generated
        CROSSING_NETWORK    // Crossing network
    }

    public enum VenueType {
        NONE,               // No venue specified
        INTERNAL,           // Internal matching engine
        CME,                // CME Group
        NASDAQ,             // Nasdaq
        NYSE,               // NYSE
        ARCA,               // NYSE Arca
        IEX,                // IEX
        DARK_POOL,          // Internal dark pool
        CROSSING            // Crossing network
    }
}
