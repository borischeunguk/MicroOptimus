package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test OrderBook implementation using CoralME patterns
 */
class OrderBookTest {

    private OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook("AAPL");
    }

    @Test
    void testEmptyBook() {
        assertTrue(orderBook.isEmpty());
        assertEquals(OrderBook.State.EMPTY, orderBook.getState());
        assertFalse(orderBook.hasBids());
        assertFalse(orderBook.hasAsks());
    }

    @Test
    void testAddBidOrder() {
        Order order = orderBook.addLimitOrder(1L, 100L, Side.BUY, 15000L, 100L, TimeInForce.DAY);

        assertNotNull(order);
        assertFalse(orderBook.isEmpty());
        assertTrue(orderBook.hasBids());
        assertEquals(15000L, orderBook.getBestBidPrice());
        assertEquals(100L, orderBook.getBestBidSize());
    }

    @Test
    void testAddAskOrder() {
        Order order = orderBook.addLimitOrder(1L, 100L, Side.SELL, 15100L, 100L, TimeInForce.DAY);

        assertNotNull(order);
        assertTrue(orderBook.hasAsks());
        assertEquals(15100L, orderBook.getBestAskPrice());
        assertEquals(100L, orderBook.getBestAskSize());
    }

    @Test
    void testSpread() {
        orderBook.addLimitOrder(1L, 100L, Side.BUY, 15000L, 100L, TimeInForce.DAY);
        orderBook.addLimitOrder(2L, 100L, Side.SELL, 15100L, 100L, TimeInForce.DAY);

        assertEquals(100L, orderBook.getSpread());
        assertEquals(OrderBook.State.NORMAL, orderBook.getState());
    }

    @Test
    void testMatchAtTopOfBook() {
        // Add resting sell order
        orderBook.addLimitOrder(1L, 100L, Side.SELL, 15000L, 100L, TimeInForce.DAY);

        // Add aggressive buy order that matches
        Order buyOrder = orderBook.addLimitOrder(2L, 101L, Side.BUY, 15000L, 100L, TimeInForce.DAY);

        // Buy order should be filled
        assertTrue(buyOrder.isFilled());
        assertEquals(100L, buyOrder.getExecutedSize());

        // Book should be empty now
        assertTrue(orderBook.isEmpty());
    }

    @Test
    void testPartialFill() {
        // Add resting sell order (50 qty)
        orderBook.addLimitOrder(1L, 100L, Side.SELL, 15000L, 50L, TimeInForce.DAY);

        // Add aggressive buy order (100 qty)
        Order buyOrder = orderBook.addLimitOrder(2L, 101L, Side.BUY, 15000L, 100L, TimeInForce.DAY);

        // Buy order should be partially filled
        assertFalse(buyOrder.isFilled());
        assertEquals(50L, buyOrder.getExecutedSize());
        assertEquals(50L, buyOrder.getRemainingQuantity());

        // Remaining should rest in book
        assertTrue(orderBook.hasBids());
        assertEquals(50L, orderBook.getBestBidSize());
    }

    @Test
    void testMarketOrder() {
        // Add resting sell order
        orderBook.addLimitOrder(1L, 100L, Side.SELL, 15000L, 100L, TimeInForce.DAY);

        // Add market buy order
        Order marketOrder = orderBook.addMarketOrder(2L, 101L, Side.BUY, 100L);

        // Market order should be filled
        assertTrue(marketOrder.isFilled());
        assertTrue(orderBook.isEmpty());
    }

    @Test
    void testIOCOrder() {
        // IOC order with no liquidity should be rejected
        Order iocOrder = orderBook.addLimitOrder(1L, 100L, Side.BUY, 15000L, 100L, TimeInForce.IOC);

        // Should be terminal (not resting)
        assertTrue(iocOrder.isTerminal());
        assertEquals(0L, iocOrder.getExecutedSize());
        assertTrue(orderBook.isEmpty());
    }

    @Test
    void testCancelOrder() {
        Order order = orderBook.addLimitOrder(1L, 100L, Side.BUY, 15000L, 100L, TimeInForce.DAY);

        assertFalse(orderBook.isEmpty());

        boolean cancelled = orderBook.cancelOrder(order.getOrderId());

        assertTrue(cancelled);
        assertTrue(orderBook.isEmpty());
    }

    @Test
    void testPriceLevels() {
        // Add multiple orders at different prices
        orderBook.addLimitOrder(1L, 100L, Side.BUY, 15000L, 100L, TimeInForce.DAY);
        orderBook.addLimitOrder(2L, 100L, Side.BUY, 14900L, 100L, TimeInForce.DAY);
        orderBook.addLimitOrder(3L, 100L, Side.BUY, 14800L, 100L, TimeInForce.DAY);

        assertEquals(3, orderBook.getBidLevels());
        assertEquals(15000L, orderBook.getBestBidPrice());
    }

    @Test
    void testTimePriority() {
        // Add two orders at same price
        Order order1 = orderBook.addLimitOrder(1L, 100L, Side.SELL, 15000L, 50L, TimeInForce.DAY);
        Order order2 = orderBook.addLimitOrder(2L, 100L, Side.SELL, 15000L, 50L, TimeInForce.DAY);

        // Aggressive order should match first order first (time priority)
        orderBook.addMarketOrder(3L, 101L, Side.BUY, 75L);

        // First order should be fully filled
        assertTrue(order1.isFilled());
        // Second order should be partially filled
        assertEquals(25L, order2.getExecutedSize());
    }
}

