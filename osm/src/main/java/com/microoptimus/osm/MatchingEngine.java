package com.microoptimus.osm;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * MatchingEngine - Matches orders based on price-time priority
 * Uses CoralME-based OrderBook for GC-free operation
 */
public class MatchingEngine {

    private final OrderBook orderBook;

    public MatchingEngine(OrderBook orderBook) {
        this.orderBook = orderBook;
    }

    /**
     * Process incoming order
     * Returns the order with updated state
     */
    public Order processOrder(long orderId, long clientId, Side side, OrderType orderType,
                             long price, long quantity, TimeInForce tif) {

        if (orderType == OrderType.MARKET) {
            return orderBook.addMarketOrder(orderId, clientId, side, quantity);
        } else {
            return orderBook.addLimitOrder(orderId, clientId, side, price, quantity, tif);
        }
    }

    /**
     * Cancel order by ID
     */
    public boolean cancelOrder(long orderId) {
        return orderBook.cancelOrder(orderId);
    }

    /**
     * Get order by ID
     */
    public Order getOrder(long orderId) {
        return orderBook.getOrder(orderId);
    }

    /**
     * Get orderbook reference
     */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * Get best bid price
     */
    public long getBestBidPrice() {
        return orderBook.getBestBidPrice();
    }

    /**
     * Get best ask price
     */
    public long getBestAskPrice() {
        return orderBook.getBestAskPrice();
    }

    /**
     * Get spread
     */
    public long getSpread() {
        return orderBook.getSpread();
    }

    /**
     * Get book state
     */
    public OrderBook.State getState() {
        return orderBook.getState();
    }

    /**
     * Check if order can be matched internally
     */
    public boolean hasLiquidity(Side side, long price) {
        if (side == Side.BUY) {
            return orderBook.hasAsks() && price >= orderBook.getBestAskPrice();
        } else {
            return orderBook.hasBids() && price <= orderBook.getBestBidPrice();
        }
    }
}

