package com.microoptimus.osm;

import com.microoptimus.common.types.Side;

/**
 * PriceLevel - Represents a price level in the order book
 * Contains doubly-linked list of orders at this price
 * Pooled for GC-free operation, following CoralME design patterns
 */
public class PriceLevel {

    // Price level attributes
    private String security;
    private long price;
    private Side side;
    private long totalSize;
    private int orderCount;

    // Intrusive doubly-linked list of orders at this price
    private Order head;
    private Order tail;

    // Intrusive doubly-linked list pointers (for OrderBook price level list)
    PriceLevel next;
    PriceLevel prev;

    public PriceLevel() {
        // Empty constructor for pooling
    }

    /**
     * Initialize price level (called when getting from pool)
     */
    void init(String security, Side side, long price) {
        this.security = security;
        this.side = side;
        this.price = price;
        this.totalSize = 0;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.prev = null;
    }

    /**
     * Reset for return to pool (GC-free)
     */
    void reset() {
        this.security = null;
        this.price = 0;
        this.side = null;
        this.totalSize = 0;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.prev = null;
    }

    /**
     * Add order to this price level (FIFO - time priority)
     */
    void addOrder(Order order) {
        if (head == null) {
            // First order at this level
            head = tail = order;
            order.prev = order.next = null;
        } else {
            // Append to tail (FIFO)
            tail.next = order;
            order.prev = tail;
            order.next = null;
            tail = order;
        }

        order.setPriceLevel(this);
        totalSize += order.getRemainingQuantity();
        orderCount++;
    }

    /**
     * Remove order from this price level
     */
    void removeOrder(Order order) {
        // Update links
        if (order.prev != null) {
            order.prev.next = order.next;
        } else {
            head = order.next;
        }

        if (order.next != null) {
            order.next.prev = order.prev;
        } else {
            tail = order.prev;
        }

        // Update totals
        totalSize -= order.getRemainingQuantity();
        orderCount--;

        // Clear order's price level reference
        order.setPriceLevel(null);
    }

    /**
     * Update size after order execution (without removing order)
     */
    void onOrderExecuted(Order order, long executedQty) {
        totalSize -= executedQty;

        // Remove order if fully filled
        if (order.isFilled()) {
            removeOrder(order);
        }
    }

    /**
     * Check if order can match at this price level
     */
    boolean canMatch(Side incomingSide, long incomingPrice) {
        if (incomingSide == Side.BUY) {
            // Buy order matches if incoming price >= this level price
            return incomingPrice >= this.price;
        } else {
            // Sell order matches if incoming price <= this level price
            return incomingPrice <= this.price;
        }
    }

    // Getters
    public String getSecurity() { return security; }
    public long getPrice() { return price; }
    public Side getSide() { return side; }
    public long getTotalQuantity() { return totalSize; }
    public int getOrderCount() { return orderCount; }
    public Order getHead() { return head; }
    public Order getTail() { return tail; }
    public boolean isEmpty() { return orderCount == 0; }

    @Override
    public String toString() {
        return "PriceLevel{" + side + " " + totalSize + "@" + price +
               ", orders=" + orderCount + "}";
    }
}

