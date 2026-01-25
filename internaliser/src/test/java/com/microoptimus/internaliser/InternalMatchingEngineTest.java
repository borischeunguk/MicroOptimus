package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InternalMatchingEngine
 */
class InternalMatchingEngineTest {

    private InternalOrderBook orderBook;
    private InternalMatchingEngine engine;
    private long orderId = 1;
    private long clientId = 100;

    @BeforeEach
    void setUp() {
        orderBook = new InternalOrderBook(1);
        engine = new InternalMatchingEngine(orderBook);
    }

    @Test
    void testAddRestingOrder() {
        // Add bid - should rest (no asks)
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10000, 100,
                TimeInForce.GTC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.RESTING, result.getStatus());
        assertEquals(0, result.getExecutedQuantity());
        assertEquals(100, result.getLeavesQuantity());
        assertNotNull(result.getRestingOrder());
        assertEquals(1, orderBook.getBidLevelCount());
    }

    @Test
    void testFullMatch() {
        // Add resting ask
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 100,
                TimeInForce.GTC, System.nanoTime());

        // Add matching bid - should fully fill
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10000, 100,
                TimeInForce.GTC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.FILLED, result.getStatus());
        assertEquals(100, result.getExecutedQuantity());
        assertEquals(0, result.getLeavesQuantity());
        assertEquals(0, orderBook.getAskLevelCount());
    }

    @Test
    void testPartialMatch() {
        // Add resting ask for 50
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 50,
                TimeInForce.GTC, System.nanoTime());

        // Add bid for 100 - should partial fill 50, rest 50
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10000, 100,
                TimeInForce.GTC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.PARTIAL_FILL_RESTING, result.getStatus());
        assertEquals(50, result.getExecutedQuantity());
        assertEquals(50, result.getLeavesQuantity());
        assertEquals(1, orderBook.getBidLevelCount());
        assertEquals(50, orderBook.getBidQuantity());
    }

    @Test
    void testIOCCancelRemaining() {
        // Add resting ask for 50
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 50,
                TimeInForce.GTC, System.nanoTime());

        // Add IOC bid for 100 - should fill 50, cancel remaining
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10000, 100,
                TimeInForce.IOC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.PARTIAL_FILL_CANCELLED, result.getStatus());
        assertEquals(50, result.getExecutedQuantity());
        assertEquals(50, result.getLeavesQuantity());
        assertEquals(0, orderBook.getBidLevelCount()); // IOC doesn't rest
    }

    @Test
    void testPriceTimePriority() {
        // Add two asks at same price
        long ask1 = nextOrderId();
        long ask2 = nextOrderId();
        engine.processOrder(ask1, clientId, Side.SELL, 10000, 50, TimeInForce.GTC, System.nanoTime());
        engine.processOrder(ask2, clientId, Side.SELL, 10000, 50, TimeInForce.GTC, System.nanoTime());

        // Add bid that matches first ask only
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10000, 50,
                TimeInForce.GTC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.FILLED, result.getStatus());
        assertEquals(50, result.getExecutedQuantity());

        // First ask should be filled, second should remain
        assertNull(orderBook.getOrder(ask1)); // Removed after fill
        assertNotNull(orderBook.getOrder(ask2)); // Still resting
    }

    @Test
    void testCancelOrder() {
        // Add resting bid
        long bidId = nextOrderId();
        engine.processOrder(bidId, clientId, Side.BUY, 10000, 100, TimeInForce.GTC, System.nanoTime());

        // Cancel it
        InternalMatchingEngine.CancelResult result = engine.cancelOrder(bidId, System.nanoTime());

        assertTrue(result.isSuccess());
        assertEquals(100, result.getCancelledQuantity());
        assertEquals(0, orderBook.getBidLevelCount());
    }

    @Test
    void testCancelNonExistentOrder() {
        InternalMatchingEngine.CancelResult result = engine.cancelOrder(999, System.nanoTime());

        assertFalse(result.isSuccess());
        assertEquals(InternalMatchingEngine.CancelResult.Status.NOT_FOUND, result.getStatus());
    }

    @Test
    void testMultiplePriceLevels() {
        // Add asks at different prices
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10100, 50, TimeInForce.GTC, System.nanoTime());
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10200, 50, TimeInForce.GTC, System.nanoTime());
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 50, TimeInForce.GTC, System.nanoTime());

        assertEquals(3, orderBook.getAskLevelCount());
        assertEquals(10000, orderBook.getBestAskPrice()); // Best ask should be lowest

        // Add large bid that sweeps all levels
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, Side.BUY, 10200, 150,
                TimeInForce.GTC, System.nanoTime());

        assertEquals(InternalMatchingEngine.MatchResult.Status.FILLED, result.getStatus());
        assertEquals(150, result.getExecutedQuantity());
        assertEquals(0, orderBook.getAskLevelCount());
    }

    @Test
    void testSpreadCalculation() {
        engine.processOrder(nextOrderId(), clientId, Side.BUY, 9900, 100, TimeInForce.GTC, System.nanoTime());
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 100, TimeInForce.GTC, System.nanoTime());

        assertEquals(9900, orderBook.getBestBidPrice());
        assertEquals(10000, orderBook.getBestAskPrice());
        assertEquals(100, orderBook.getSpread());
        assertFalse(orderBook.isCrossed());
    }

    @Test
    void testAlgoSliceOrder() {
        // Add resting ask
        engine.processOrder(nextOrderId(), clientId, Side.SELL, 10000, 100, TimeInForce.GTC, System.nanoTime());

        // Add algo slice bid
        long parentOrderId = 1000;
        InternalMatchingEngine.MatchResult result = engine.processOrder(
                nextOrderId(), clientId, parentOrderId, Side.BUY, 10000, 100,
                TimeInForce.GTC, System.nanoTime(), Order.OrderFlowType.ALGO_SLICE);

        assertEquals(InternalMatchingEngine.MatchResult.Status.FILLED, result.getStatus());
    }

    private long nextOrderId() {
        return orderId++;
    }
}
