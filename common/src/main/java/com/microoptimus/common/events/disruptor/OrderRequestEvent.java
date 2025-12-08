package com.microoptimus.common.events.disruptor;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * OrderRequestEvent - Disruptor event for order requests (Signal → OSM)
 *
 * Pre-allocated, mutable event object used in LMAX Disruptor RingBuffer.
 * Represents an order request from market making strategy to the matching engine.
 *
 * Design: GC-free via object pooling (Disruptor pre-allocates all events)
 */
public class OrderRequestEvent {

    // Order identification
    private long orderId;
    private long clientId;

    // Order details
    private String symbol;
    private Side side;
    private OrderType orderType;
    private long price;  // Scaled long (e.g., 15000 = $150.00)
    private long quantity;
    private TimeInForce timeInForce;

    // Timestamp when event was created (nanoseconds)
    private long timestamp;

    // Sequence number for ordering
    private long sequenceNumber;

    // Source of the order
    private OrderSource source;

    public enum OrderSource {
        MARKET_MAKING,
        RISK_MANAGEMENT,
        MANUAL,
        EXTERNAL
    }

    /**
     * Default constructor (required by Disruptor)
     */
    public OrderRequestEvent() {
        this.orderType = OrderType.LIMIT;
        this.timeInForce = TimeInForce.GTC;
        this.source = OrderSource.MARKET_MAKING;
    }

    /**
     * Set all fields (called by producer)
     */
    public void set(long orderId, long clientId, String symbol, Side side, OrderType orderType,
                    long price, long quantity, TimeInForce timeInForce,
                    OrderSource source, long timestamp, long sequenceNumber) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.timeInForce = timeInForce;
        this.source = source;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Convenience method for limit orders
     */
    public void setLimitOrder(long orderId, long clientId, String symbol, Side side,
                              long price, long quantity, TimeInForce timeInForce,
                              long timestamp, long sequenceNumber) {
        set(orderId, clientId, symbol, side, OrderType.LIMIT, price, quantity,
            timeInForce, OrderSource.MARKET_MAKING, timestamp, sequenceNumber);
    }

    /**
     * Convenience method for market orders
     */
    public void setMarketOrder(long orderId, long clientId, String symbol, Side side,
                               long quantity, long timestamp, long sequenceNumber) {
        set(orderId, clientId, symbol, side, OrderType.MARKET, 0, quantity,
            TimeInForce.IOC, OrderSource.MARKET_MAKING, timestamp, sequenceNumber);
    }

    /**
     * Reset event for reuse (called by Disruptor after processing)
     */
    public void clear() {
        this.orderId = 0;
        this.clientId = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = OrderType.LIMIT;
        this.price = 0;
        this.quantity = 0;
        this.timeInForce = TimeInForce.GTC;
        this.source = OrderSource.MARKET_MAKING;
        this.timestamp = 0;
        this.sequenceNumber = 0;
    }

    // Getters
    public long getOrderId() { return orderId; }
    public long getClientId() { return clientId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getOrderType() { return orderType; }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public TimeInForce getTimeInForce() { return timeInForce; }
    public OrderSource getSource() { return source; }
    public long getTimestamp() { return timestamp; }
    public long getSequenceNumber() { return sequenceNumber; }

    @Override
    public String toString() {
        return String.format("OrderRequest[id=%d, %s %s %d@%d, tif=%s, seq=%d]",
                orderId, side, symbol, quantity, price, timeInForce, sequenceNumber);
    }
}

