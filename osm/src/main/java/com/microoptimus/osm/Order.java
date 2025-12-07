package com.microoptimus.osm;

import com.microoptimus.common.types.Side;

/**
 * Order - Represents a single order in the order book
 * Pooled for GC-free operation
 */
public class Order {

    private long orderId;
    private String symbol;
    private Side side;
    private long price;
    private long quantity;
    private long filledQuantity;
    private long timestamp;

    // Linked list pointers for price level
    Order next;
    Order prev;

    public Order() {
    }

    public void reset() {
        this.orderId = 0;
        this.symbol = null;
        this.side = null;
        this.price = 0;
        this.quantity = 0;
        this.filledQuantity = 0;
        this.timestamp = 0;
        this.next = null;
        this.prev = null;
    }

    // Getters and setters
    public long getOrderId() { return orderId; }
    public void setOrderId(long orderId) { this.orderId = orderId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }

    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }

    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }

    public long getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(long filledQuantity) { this.filledQuantity = filledQuantity; }

    public long getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

