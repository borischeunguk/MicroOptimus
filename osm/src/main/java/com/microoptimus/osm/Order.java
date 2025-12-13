package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;

/**
 * Order - Represents a single order in the order book
 * Pooled for GC-free operation, following CoralME design patterns
 */
public class Order {

    // Order identifiers
    private long orderId;
    private long clientId;
    private String symbol;

    // Order attributes
    private Side side;
    private OrderType orderType;
    private long price;
    private long originalSize;
    private long executedSize;
    private TimeInForce timeInForce;

    // Timestamps
    private long submitTimestamp;
    private long acceptTimestamp;

    // State
    private OrderState state;
    private PriceLevel priceLevel;

    // Liquidity source tracking (for internalization priority)
    private LiquiditySource liquiditySource;

    // Intrusive linked list pointers (for PriceLevel)
    Order next;
    Order prev;

    public enum OrderState {
        NEW,           // Created but not yet submitted
        ACCEPTED,      // Accepted by book
        PARTIAL_FILL,  // Partially executed
        FILLED,        // Fully executed
        CANCELLED,     // Cancelled
        REJECTED       // Rejected
    }

    public Order() {
        this.state = OrderState.NEW;
    }

    /**
     * Initialize order with all fields (called when getting from pool)
     */
    void init(long orderId, long clientId, String symbol, Side side, OrderType orderType,
              long price, long size, TimeInForce tif, long submitTimestamp) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.originalSize = size;
        this.executedSize = 0;
        this.timeInForce = tif;
        this.submitTimestamp = submitTimestamp;
        this.acceptTimestamp = -1;
        this.state = OrderState.NEW;
        this.priceLevel = null;
        this.liquiditySource = null; // Will be set separately
        this.next = null;
        this.prev = null;
    }

    /**
     * Initialize order with liquidity source
     */
    void init(long orderId, long clientId, String symbol, Side side, OrderType orderType,
              long price, long size, TimeInForce tif, long submitTimestamp,
              LiquiditySource liquiditySource) {
        init(orderId, clientId, symbol, side, orderType, price, size, tif, submitTimestamp);
        this.liquiditySource = liquiditySource;
    }

    /**
     * Reset order for return to pool (GC-free)
     */
    void reset() {
        this.orderId = 0;
        this.clientId = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.originalSize = 0;
        this.executedSize = 0;
        this.timeInForce = null;
        this.submitTimestamp = 0;
        this.acceptTimestamp = 0;
        this.state = OrderState.NEW;
        this.priceLevel = null;
        this.liquiditySource = null;
        this.next = null;
        this.prev = null;
    }

    /**
     * Execute a quantity of this order
     */
    void execute(long quantity) {
        this.executedSize += quantity;
        if (this.executedSize >= this.originalSize) {
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIAL_FILL;
        }
    }

    /**
     * Mark order as accepted
     */
    void accept() {
        this.state = OrderState.ACCEPTED;
        this.acceptTimestamp = System.nanoTime();
    }

    /**
     * Mark order as cancelled
     */
    void cancel() {
        this.state = OrderState.CANCELLED;
    }

    /**
     * Mark order as rejected
     */
    void reject() {
        this.state = OrderState.REJECTED;
    }

    // Getters
    public long getOrderId() { return orderId; }
    public long getClientId() { return clientId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public long getPrice() { return price; }
    public long getOriginalSize() { return originalSize; }
    public long getExecutedSize() { return executedSize; }
    public long getRemainingQuantity() { return originalSize - executedSize; }
    public TimeInForce getTimeInForce() { return timeInForce; }
    public long getSubmitTimestamp() { return submitTimestamp; }
    public long getAcceptTimestamp() { return acceptTimestamp; }
    public OrderState getState() { return state; }
    public PriceLevel getPriceLevel() { return priceLevel; }

    // Liquidity source methods
    public LiquiditySource getLiquiditySource() { return liquiditySource; }
    void setLiquiditySource(LiquiditySource source) { this.liquiditySource = source; }

    void setPriceLevel(PriceLevel priceLevel) {
        this.priceLevel = priceLevel;
    }

    void setAcceptTimestamp(long timestamp) {
        this.acceptTimestamp = timestamp;
    }

    // State queries
    public boolean isFilled() {
        return state == OrderState.FILLED;
    }

    public boolean isTerminal() {
        return state == OrderState.FILLED ||
               state == OrderState.CANCELLED ||
               state == OrderState.REJECTED;
    }

    public boolean isResting() {
        return state == OrderState.ACCEPTED || state == OrderState.PARTIAL_FILL;
    }

    @Override
    public String toString() {
        return "Order{id=" + orderId + ", " + side + " " + originalSize +
               "@" + price + ", exec=" + executedSize + ", state=" + state + "}";
    }
}
