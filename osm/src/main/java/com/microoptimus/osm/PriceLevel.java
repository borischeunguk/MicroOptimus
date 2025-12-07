package com.microoptimus.osm;

import com.microoptimus.common.types.Side;

/**
 * PriceLevel - Represents a price level in the order book
 * Contains doubly-linked list of orders at this price
 */
public class PriceLevel {

    private final long price;
    private final Side side;
    private long totalQuantity;
    private int orderCount;

    // Head and tail of order linked list
    private Order head;
    private Order tail;

    public PriceLevel(long price, Side side) {
        this.price = price;
        this.side = side;
        this.totalQuantity = 0;
        this.orderCount = 0;
    }

    /**
     * Add order to this price level (FIFO - time priority)
     */
    public void addOrder(Order order) {
        if (tail == null) {
            head = tail = order;
        } else {
            tail.next = order;
            order.prev = tail;
            tail = order;
        }
        totalQuantity += order.getQuantity();
        orderCount++;
    }

    /**
     * Remove order from this price level
     */
    public void removeOrder(Order order) {
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

        totalQuantity -= order.getRemainingQuantity();
        orderCount--;
    }

    public long getPrice() { return price; }
    public Side getSide() { return side; }
    public long getTotalQuantity() { return totalQuantity; }
    public int getOrderCount() { return orderCount; }
    public Order getHead() { return head; }
    public boolean isEmpty() { return head == null; }
}

