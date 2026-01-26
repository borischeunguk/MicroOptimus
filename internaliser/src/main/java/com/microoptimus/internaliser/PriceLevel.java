package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;

/**
 * PriceLevel - Pooled price level for GC-free operation
 *
 * Design principles:
 * - Intrusive doubly-linked list of orders (no Node wrappers)
 * - FIFO order within same price (time priority)
 * - Object pooling via init()/reset() pattern
 */
public class PriceLevel {

    // Price level attributes
    private int symbolIndex;
    private long price;
    private Side side;
    private long totalQuantity;
    private int orderCount;

    // Intrusive doubly-linked list of orders at this price
    private Order head;
    private Order tail;

    // Intrusive doubly-linked list pointers (for orderbook price level chain)
    PriceLevel next;
    PriceLevel prev;

    // Pool management
    private boolean inPool;

    public PriceLevel() {
        this.inPool = true;
    }

    /**
     * Initialize price level - called when acquiring from pool
     */
    public void init(int symbolIndex, Side side, long price) {
        this.symbolIndex = symbolIndex;
        this.side = side;
        this.price = price;
        this.totalQuantity = 0;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.prev = null;
        this.inPool = false;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.symbolIndex = 0;
        this.price = 0;
        this.side = null;
        this.totalQuantity = 0;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
        this.next = null;
        this.prev = null;
        this.inPool = true;
    }

    /**
     * Add order to this price level (FIFO - append to tail)
     */
    public void addOrder(Order order) {
        if (head == null) {
            // First order at this level
            head = tail = order;
            order.prev = null;
            order.next = null;
        } else {
            // Append to tail (time priority)
            tail.next = order;
            order.prev = tail;
            order.next = null;
            tail = order;
        }

        order.setPriceLevel(this);
        totalQuantity += order.getLeavesQuantity();
        orderCount++;
    }

    /**
     * Remove order from this price level
     */
    public void removeOrder(Order order) {
        // Update linked list pointers
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
        totalQuantity -= order.getLeavesQuantity();
        orderCount--;

        // Clear order's price level reference
        order.setPriceLevel(null);
        order.prev = null;
        order.next = null;
    }

    /**
     * Update quantity after order execution (without removal)
     * Note: Order removal is handled by InternalOrderBook.removeOrder()
     */
    public void onOrderExecuted(Order order, long executedQty) {
        totalQuantity -= executedQty;
        // Don't auto-remove filled orders here - let InternalOrderBook.removeOrder() handle it
        // to ensure proper level cleanup and pool management
    }

    /**
     * Check if incoming order can match at this price level
     */
    public boolean canMatch(Side incomingSide, long incomingPrice) {
        if (incomingSide == Side.BUY) {
            // Buy order matches if bid >= ask (this level price)
            return incomingPrice >= this.price;
        } else {
            // Sell order matches if ask <= bid (this level price)
            return incomingPrice <= this.price;
        }
    }

    /**
     * Check if this price level improves on another for the given side
     */
    public boolean betterPriceThan(PriceLevel other) {
        if (side == Side.BUY) {
            // Higher bid is better
            return this.price > other.price;
        } else {
            // Lower ask is better
            return this.price < other.price;
        }
    }

    // Getters
    public int getSymbolIndex() { return symbolIndex; }
    public long getPrice() { return price; }
    public Side getSide() { return side; }
    public long getTotalQuantity() { return totalQuantity; }
    public int getOrderCount() { return orderCount; }
    public Order getHead() { return head; }
    public Order getTail() { return tail; }
    public boolean isEmpty() { return orderCount == 0; }
    public boolean isInPool() { return inPool; }

    @Override
    public String toString() {
        return "PriceLevel{" + side + " " + totalQuantity + "@" + price +
               ", orders=" + orderCount + "}";
    }
}
