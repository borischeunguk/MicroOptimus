package com.microoptimus.internaliser;

/**
 * ExecutionReportPool - Lock-free object pool for ExecutionReport instances
 */
public class ExecutionReportPool {

    private static final int DEFAULT_INITIAL_SIZE = 32768;
    private static final int DEFAULT_EXPAND_SIZE = 8192;

    private ExecutionReport[] reports;
    private int[] freeList;
    private int freeListHead;
    private int capacity;
    private int activeCount;
    private final int expandSize;

    public ExecutionReportPool() {
        this(DEFAULT_INITIAL_SIZE, DEFAULT_EXPAND_SIZE);
    }

    public ExecutionReportPool(int initialSize, int expandSize) {
        this.expandSize = expandSize;
        this.capacity = initialSize;
        this.reports = new ExecutionReport[initialSize];
        this.freeList = new int[initialSize];

        for (int i = 0; i < initialSize; i++) {
            reports[i] = new ExecutionReport();
            freeList[i] = i + 1;
        }
        freeList[initialSize - 1] = -1;
        freeListHead = 0;
        activeCount = 0;
    }

    public ExecutionReport acquire() {
        if (freeListHead == -1) {
            expand();
        }

        int index = freeListHead;
        freeListHead = freeList[index];
        activeCount++;
        return reports[index];
    }

    public void release(ExecutionReport report) {
        int index = findIndex(report);
        if (index < 0) {
            throw new IllegalArgumentException("ExecutionReport not from this pool");
        }

        report.reset();
        freeList[index] = freeListHead;
        freeListHead = index;
        activeCount--;
    }

    private void expand() {
        int oldCapacity = capacity;
        int newCapacity = capacity + expandSize;

        ExecutionReport[] newReports = new ExecutionReport[newCapacity];
        int[] newFreeList = new int[newCapacity];

        System.arraycopy(reports, 0, newReports, 0, oldCapacity);
        System.arraycopy(freeList, 0, newFreeList, 0, oldCapacity);

        for (int i = oldCapacity; i < newCapacity; i++) {
            newReports[i] = new ExecutionReport();
            newFreeList[i] = i + 1;
        }
        newFreeList[newCapacity - 1] = -1;
        freeListHead = oldCapacity;

        this.reports = newReports;
        this.freeList = newFreeList;
        this.capacity = newCapacity;
    }

    private int findIndex(ExecutionReport report) {
        for (int i = 0; i < capacity; i++) {
            if (reports[i] == report) {
                return i;
            }
        }
        return -1;
    }

    public int getCapacity() { return capacity; }
    public int getActiveCount() { return activeCount; }
}
