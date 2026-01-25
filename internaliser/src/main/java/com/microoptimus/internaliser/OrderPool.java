package com.microoptimus.internaliser;

/**
 * OrderPool - Lock-free object pool for Order instances
 *
 * Design principles:
 * - Pre-allocated array for zero allocation in hot path
 * - Simple free list with array-based indexing
 * - No synchronization (single-threaded use)
 * - Expandable when exhausted
 */
public class OrderPool {

    private static final int DEFAULT_INITIAL_SIZE = 65536;
    private static final int DEFAULT_EXPAND_SIZE = 16384;

    private Order[] orders;
    private int[] freeList;
    private int freeListHead;
    private int capacity;
    private int activeCount;
    private final int expandSize;

    // Statistics
    private long totalAcquired;
    private long totalReleased;
    private int peakActive;

    public OrderPool() {
        this(DEFAULT_INITIAL_SIZE, DEFAULT_EXPAND_SIZE);
    }

    public OrderPool(int initialSize, int expandSize) {
        this.expandSize = expandSize;
        this.capacity = initialSize;
        this.orders = new Order[initialSize];
        this.freeList = new int[initialSize];

        // Initialize pool with pre-allocated orders
        for (int i = 0; i < initialSize; i++) {
            orders[i] = new Order();
            freeList[i] = i + 1; // Point to next free slot
        }
        freeList[initialSize - 1] = -1; // End of list marker
        freeListHead = 0;
        activeCount = 0;
    }

    /**
     * Acquire an order from the pool
     */
    public Order acquire() {
        if (freeListHead == -1) {
            expand();
        }

        int index = freeListHead;
        freeListHead = freeList[index];

        Order order = orders[index];
        activeCount++;
        totalAcquired++;

        if (activeCount > peakActive) {
            peakActive = activeCount;
        }

        return order;
    }

    /**
     * Release an order back to the pool
     */
    public void release(Order order) {
        // Find order index (orders know their index through identity)
        int index = findIndex(order);
        if (index < 0) {
            throw new IllegalArgumentException("Order not from this pool");
        }

        order.reset();
        freeList[index] = freeListHead;
        freeListHead = index;
        activeCount--;
        totalReleased++;
    }

    /**
     * Expand the pool when exhausted
     */
    private void expand() {
        int oldCapacity = capacity;
        int newCapacity = capacity + expandSize;

        Order[] newOrders = new Order[newCapacity];
        int[] newFreeList = new int[newCapacity];

        // Copy existing orders
        System.arraycopy(orders, 0, newOrders, 0, oldCapacity);
        System.arraycopy(freeList, 0, newFreeList, 0, oldCapacity);

        // Initialize new orders
        for (int i = oldCapacity; i < newCapacity; i++) {
            newOrders[i] = new Order();
            newFreeList[i] = i + 1;
        }
        newFreeList[newCapacity - 1] = -1;

        // Update free list head to point to first new slot
        freeListHead = oldCapacity;

        this.orders = newOrders;
        this.freeList = newFreeList;
        this.capacity = newCapacity;
    }

    /**
     * Find index of order in pool (O(n) - used only for release)
     */
    private int findIndex(Order order) {
        for (int i = 0; i < capacity; i++) {
            if (orders[i] == order) {
                return i;
            }
        }
        return -1;
    }

    // Statistics and monitoring
    public int getCapacity() { return capacity; }
    public int getActiveCount() { return activeCount; }
    public int getAvailableCount() { return capacity - activeCount; }
    public long getTotalAcquired() { return totalAcquired; }
    public long getTotalReleased() { return totalReleased; }
    public int getPeakActive() { return peakActive; }

    public double getUtilization() {
        return capacity > 0 ? (double) activeCount / capacity : 0.0;
    }

    @Override
    public String toString() {
        return String.format("OrderPool[capacity=%d, active=%d, available=%d, utilization=%.1f%%]",
                capacity, activeCount, getAvailableCount(), getUtilization() * 100);
    }
}
