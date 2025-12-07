package com.microoptimus.osm;

import java.util.ArrayList;
import java.util.List;

/**
 * MatchingEngine - Matches orders based on price-time priority
 */
public class MatchingEngine {

    private final OrderBook orderBook;
    private long executionIdCounter = 0;

    public MatchingEngine(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    /**
     * Match an incoming order against the order book
     * Returns list of executions (fills)
     */
    public List<Execution> matchOrder(Order order) {
        List<Execution> executions = new ArrayList<>();

        // TODO: Implement matching logic
        // - Match against opposite side
        // - Price-time priority
        // - Generate execution events

        return executions;
    }

    /**
     * Execution represents a trade between two orders
     */
    public static class Execution {
        private final long executionId;
        private final long makerOrderId;
        private final long takerOrderId;
        private final long price;
        private final long quantity;

        public Execution(long executionId, long makerOrderId, long takerOrderId,
                        long price, long quantity) {
            this.executionId = executionId;
            this.makerOrderId = makerOrderId;
            this.takerOrderId = takerOrderId;
            this.price = price;
            this.quantity = quantity;
        }

        public long getExecutionId() { return executionId; }
        public long getMakerOrderId() { return makerOrderId; }
        public long getTakerOrderId() { return takerOrderId; }
        public long getPrice() { return price; }
        public long getQuantity() { return quantity; }
    }
}

