package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;

/**
 * Order - Pooled order object for GC-free operation
 *
 * Design principles:
 * - Object pooling via init()/reset() pattern
 * - Intrusive linked list for price level membership
 * - No object allocation after initialization
 */
public class Order {

    // Order identifiers
    private long orderId;
    private long clientId;
    private long parentOrderId; // For algo slices
    private int symbolIndex; // Index into symbol table (avoid String allocation)

    // Order attributes
    private Side side;
    private OrderType orderType;
    private long price;
    private long originalQuantity;
    private long executedQuantity;
    private long leavesQuantity;
    private TimeInForce timeInForce;

    // Flow classification
    private OrderFlowType flowType;

    // Timestamps (nanoseconds)
    private long submitTimestamp;
    private long acceptTimestamp;
    private long lastUpdateTimestamp;

    // State
    private OrderState state;
    private PriceLevel priceLevel;

    // Intrusive linked list pointers (for PriceLevel)
    Order next;
    Order prev;

    // Pool management
    private boolean inPool;

    public enum OrderState {
        NEW,           // Created but not yet submitted
        PENDING,       // Awaiting validation
        ACCEPTED,      // Accepted by book
        PARTIAL_FILL,  // Partially executed
        FILLED,        // Fully executed
        CANCELLED,     // Cancelled
        REJECTED,      // Rejected
        EXPIRED        // Expired (for DAY orders)
    }

    public enum OrderFlowType {
        DMA,           // Direct Market Access
        PRINCIPAL,     // Principal/market-making
        ALGO_SLICE     // Algorithmic execution slice
    }

    public Order() {
        this.state = OrderState.NEW;
        this.inPool = true;
    }

    /**
     * Initialize order - called when acquiring from pool
     */
    public void init(long orderId, long clientId, int symbolIndex, Side side, OrderType orderType,
                     long price, long quantity, TimeInForce tif, long submitTimestamp) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.parentOrderId = 0;
        this.symbolIndex = symbolIndex;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.originalQuantity = quantity;
        this.executedQuantity = 0;
        this.leavesQuantity = quantity;
        this.timeInForce = tif;
        this.flowType = OrderFlowType.DMA;
        this.submitTimestamp = submitTimestamp;
        this.acceptTimestamp = 0;
        this.lastUpdateTimestamp = submitTimestamp;
        this.state = OrderState.NEW;
        this.priceLevel = null;
        this.next = null;
        this.prev = null;
        this.inPool = false;
    }

    /**
     * Initialize order with flow type and parent order ID (for algo slices)
     */
    public void init(long orderId, long clientId, long parentOrderId, int symbolIndex,
                     Side side, OrderType orderType, long price, long quantity,
                     TimeInForce tif, long submitTimestamp, OrderFlowType flowType) {
        init(orderId, clientId, symbolIndex, side, orderType, price, quantity, tif, submitTimestamp);
        this.parentOrderId = parentOrderId;
        this.flowType = flowType;
    }

    /**
     * Reset order - called when returning to pool
     */
    public void reset() {
        this.orderId = 0;
        this.clientId = 0;
        this.parentOrderId = 0;
        this.symbolIndex = 0;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.originalQuantity = 0;
        this.executedQuantity = 0;
        this.leavesQuantity = 0;
        this.timeInForce = null;
        this.flowType = null;
        this.submitTimestamp = 0;
        this.acceptTimestamp = 0;
        this.lastUpdateTimestamp = 0;
        this.state = OrderState.NEW;
        this.priceLevel = null;
        this.next = null;
        this.prev = null;
        this.inPool = true;
    }

    /**
     * Execute a quantity - returns actual executed quantity
     */
    public long execute(long quantity, long timestamp) {
        long actualQty = Math.min(quantity, leavesQuantity);
        this.executedQuantity += actualQty;
        this.leavesQuantity -= actualQty;
        this.lastUpdateTimestamp = timestamp;

        if (this.leavesQuantity == 0) {
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIAL_FILL;
        }

        return actualQty;
    }

    /**
     * Mark order as accepted
     */
    public void accept(long timestamp) {
        this.state = OrderState.ACCEPTED;
        this.acceptTimestamp = timestamp;
        this.lastUpdateTimestamp = timestamp;
    }

    /**
     * Mark order as cancelled - returns leaves quantity for ack
     */
    public long cancel(long timestamp) {
        long qty = this.leavesQuantity;
        this.state = OrderState.CANCELLED;
        this.leavesQuantity = 0;
        this.lastUpdateTimestamp = timestamp;
        return qty;
    }

    /**
     * Mark order as rejected
     */
    public void reject(long timestamp) {
        this.state = OrderState.REJECTED;
        this.lastUpdateTimestamp = timestamp;
    }

    /**
     * Mark order as expired
     */
    public void expire(long timestamp) {
        this.state = OrderState.EXPIRED;
        this.lastUpdateTimestamp = timestamp;
    }

    // Getters
    public long getOrderId() { return orderId; }
    public long getClientId() { return clientId; }
    public long getParentOrderId() { return parentOrderId; }
    public int getSymbolIndex() { return symbolIndex; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public long getPrice() { return price; }
    public long getOriginalQuantity() { return originalQuantity; }
    public long getExecutedQuantity() { return executedQuantity; }
    public long getLeavesQuantity() { return leavesQuantity; }
    public TimeInForce getTimeInForce() { return timeInForce; }
    public OrderFlowType getFlowType() { return flowType; }
    public long getSubmitTimestamp() { return submitTimestamp; }
    public long getAcceptTimestamp() { return acceptTimestamp; }
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public OrderState getState() { return state; }
    public PriceLevel getPriceLevel() { return priceLevel; }
    public boolean isInPool() { return inPool; }

    // Setters for internal use
    void setPriceLevel(PriceLevel priceLevel) { this.priceLevel = priceLevel; }
    void setFlowType(OrderFlowType flowType) { this.flowType = flowType; }

    // State queries
    public boolean isFilled() { return state == OrderState.FILLED; }
    public boolean isCancelled() { return state == OrderState.CANCELLED; }
    public boolean isRejected() { return state == OrderState.REJECTED; }
    public boolean isExpired() { return state == OrderState.EXPIRED; }

    public boolean isTerminal() {
        return state == OrderState.FILLED ||
               state == OrderState.CANCELLED ||
               state == OrderState.REJECTED ||
               state == OrderState.EXPIRED;
    }

    public boolean isResting() {
        return state == OrderState.ACCEPTED || state == OrderState.PARTIAL_FILL;
    }

    public boolean isActive() {
        return !isTerminal() && !isInPool();
    }

    @Override
    public String toString() {
        return "Order{id=" + orderId + ", " + side + " " + originalQuantity +
               "@" + price + ", leaves=" + leavesQuantity + ", state=" + state +
               ", flow=" + flowType + "}";
    }
}
