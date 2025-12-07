package com.microoptimus.osm;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * OrderStateManager - Main coordinator for OSM module
 * Decides whether to match internally or route to CME
 * Uses CoralME-based orderbook for GC-free operation
 */
public class OrderStateManager {

    private final String symbol;
    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine;

    /**
     * Create OSM for symbol
     */
    public OrderStateManager(String symbol) {
        this(symbol, true);
    }

    /**
     * Create OSM with trade-to-self configuration
     */
    public OrderStateManager(String symbol, boolean allowTradeToSelf) {
        this.symbol = symbol;
        this.orderBook = new OrderBook(symbol, allowTradeToSelf);
        this.matchingEngine = new MatchingEngine(orderBook);
    }

    /**
     * Process incoming order request
     * Decides: match internally or route to CME
     */
    public OrderResult processOrder(long orderId, long clientId, Side side,
                                   OrderType orderType, long price, long quantity,
                                   TimeInForce tif) {

        // Check if order can be matched internally
        boolean canMatchInternally = shouldMatchInternally(side, orderType, price);

        if (canMatchInternally) {
            // Match internally
            Order order = matchingEngine.processOrder(
                orderId, clientId, side, orderType, price, quantity, tif);

            if (order.isFilled()) {
                return OrderResult.FILLED_INTERNALLY;
            } else if (order.getRemainingQuantity() > 0 && tif != TimeInForce.IOC) {
                return OrderResult.RESTING_IN_BOOK;
            } else {
                return OrderResult.PARTIAL_FILL_SEND_TO_CME;
            }
        } else {
            // Route to CME
            return OrderResult.ROUTE_TO_CME;
        }
    }

    /**
     * Cancel order
     */
    public boolean cancelOrder(long orderId) {
        return matchingEngine.cancelOrder(orderId);
    }

    /**
     * Get order
     */
    public Order getOrder(long orderId) {
        return matchingEngine.getOrder(orderId);
    }

    /**
     * Decide if order should match internally
     * Business logic for routing decision
     */
    private boolean shouldMatchInternally(Side side, OrderType orderType, long price) {
        // Market orders always try internal matching first
        if (orderType == OrderType.MARKET) {
            return true;
        }

        // Check if there's matching liquidity at this price
        return matchingEngine.hasLiquidity(side, price);
    }

    /**
     * Get orderbook reference
     */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * Get matching engine reference
     */
    public MatchingEngine getMatchingEngine() {
        return matchingEngine;
    }

    /**
     * Get symbol
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * Order processing result
     */
    public enum OrderResult {
        FILLED_INTERNALLY,
        RESTING_IN_BOOK,
        PARTIAL_FILL_SEND_TO_CME,
        ROUTE_TO_CME
    }
}

