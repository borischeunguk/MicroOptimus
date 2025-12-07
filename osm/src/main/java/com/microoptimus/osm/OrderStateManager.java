package com.microoptimus.osm;

/**
 * OrderStateManager - Main coordinator for OSM module
 * Decides whether to match internally or route to CME
 */
public class OrderStateManager {

    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine;

    public OrderStateManager(String symbol) {
        this.orderBook = new OrderBook(symbol);
        this.matchingEngine = new MatchingEngine(orderBook);
    }

    /**
     * Process incoming order request
     * Decides: match internally or route to CME
     */
    public void processOrder(long orderId, String symbol, long price, long quantity) {
        // TODO: Implement routing logic
        // if (shouldMatchInternally()) { ... }
        // else { routeToCME(); }
    }

    private boolean shouldMatchInternally(long orderId) {
        // TODO: Business logic for routing decision
        return false;
    }
}

