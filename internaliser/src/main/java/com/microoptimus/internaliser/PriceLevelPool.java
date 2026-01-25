package com.microoptimus.internaliser;

/**
 * PriceLevelPool - Lock-free object pool for PriceLevel instances
 *
 * Design principles:
 * - Pre-allocated array for zero allocation in hot path
 * - Simple free list with array-based indexing
 * - No synchronization (single-threaded use)
 * - Expandable when exhausted
 */
public class PriceLevelPool {

    private static final int DEFAULT_INITIAL_SIZE = 4096;
    private static final int DEFAULT_EXPAND_SIZE = 1024;

    private PriceLevel[] levels;
    private int[] freeList;
    private int freeListHead;
    private int capacity;
    private int activeCount;
    private final int expandSize;

    // Statistics
    private long totalAcquired;
    private long totalReleased;
    private int peakActive;

    public PriceLevelPool() {
        this(DEFAULT_INITIAL_SIZE, DEFAULT_EXPAND_SIZE);
    }

    public PriceLevelPool(int initialSize, int expandSize) {
        this.expandSize = expandSize;
        this.capacity = initialSize;
        this.levels = new PriceLevel[initialSize];
        this.freeList = new int[initialSize];

        // Initialize pool with pre-allocated price levels
        for (int i = 0; i < initialSize; i++) {
            levels[i] = new PriceLevel();
            freeList[i] = i + 1;
        }
        freeList[initialSize - 1] = -1;
        freeListHead = 0;
        activeCount = 0;
    }

    /**
     * Acquire a price level from the pool
     */
    public PriceLevel acquire() {
        if (freeListHead == -1) {
            expand();
        }

        int index = freeListHead;
        freeListHead = freeList[index];

        PriceLevel level = levels[index];
        activeCount++;
        totalAcquired++;

        if (activeCount > peakActive) {
            peakActive = activeCount;
        }

        return level;
    }

    /**
     * Release a price level back to the pool
     */
    public void release(PriceLevel level) {
        int index = findIndex(level);
        if (index < 0) {
            throw new IllegalArgumentException("PriceLevel not from this pool");
        }

        level.reset();
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

        PriceLevel[] newLevels = new PriceLevel[newCapacity];
        int[] newFreeList = new int[newCapacity];

        System.arraycopy(levels, 0, newLevels, 0, oldCapacity);
        System.arraycopy(freeList, 0, newFreeList, 0, oldCapacity);

        for (int i = oldCapacity; i < newCapacity; i++) {
            newLevels[i] = new PriceLevel();
            newFreeList[i] = i + 1;
        }
        newFreeList[newCapacity - 1] = -1;

        freeListHead = oldCapacity;

        this.levels = newLevels;
        this.freeList = newFreeList;
        this.capacity = newCapacity;
    }

    private int findIndex(PriceLevel level) {
        for (int i = 0; i < capacity; i++) {
            if (levels[i] == level) {
                return i;
            }
        }
        return -1;
    }

    // Statistics
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
        return String.format("PriceLevelPool[capacity=%d, active=%d, available=%d, utilization=%.1f%%]",
                capacity, activeCount, getAvailableCount(), getUtilization() * 100);
    }
}
