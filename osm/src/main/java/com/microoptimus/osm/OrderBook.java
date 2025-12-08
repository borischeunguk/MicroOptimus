package com.microoptimus.osm;

import com.coralblocks.coralpool.ArrayObjectPool;
import com.coralblocks.coralpool.ObjectBuilder;
import com.coralblocks.coralpool.ObjectPool;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import com.coralblocks.coralds.map.LongMap;


/**
 * OrderBook - Maintains price levels and orders
 * Based on CoralME design patterns for GC-free operation
 *
 * Key features:
 * - Object pooling for Orders and PriceLevels (zero GC)
 * - Intrusive doubly-linked lists for cache locality
 * - Price-time priority matching
 * - O(1) order lookup via LongMap
 */
public class OrderBook {

    /**
     * Tunable pool sizes
     */
    public static int ORDER_POOL_INITIAL_SIZE = 128;
    public static int PRICE_LEVEL_POOL_INITIAL_SIZE = 64;

    // Book state
    public enum State { NORMAL, LOCKED, CROSSED, ONESIDED, EMPTY }

    private final String symbol;

    // Object pools for GC-free operation
    private final ObjectPool<Order> orderPool;
    private final ObjectPool<PriceLevel> priceLevelPool;

    // Price level lists (doubly-linked, sorted by price)
    // head[0] = best bid, head[1] = best ask
    // tail[0] = worst bid, tail[1] = worst ask
    private final PriceLevel[] head = new PriceLevel[2];
    private final PriceLevel[] tail = new PriceLevel[2];
    private final int[] levelCount = new int[] { 0, 0 };

    // Fast O(1) order lookup
    private final LongMap<Order> orders = new LongMap<>();

    // Execution tracking
    private long executionIdCounter = 0;
    private long matchIdCounter = 0;
    private long lastExecutedPrice = Long.MAX_VALUE;

    // Configuration
    private final boolean allowTradeToSelf;

    /**
     * Create order book for symbol
     */
    public OrderBook(String symbol) {
        this(symbol, true);
    }

    /**
     * Create order book with trade-to-self configuration
     */
    public OrderBook(String symbol, boolean allowTradeToSelf) {
        this.symbol = symbol;
        this.allowTradeToSelf = allowTradeToSelf;

        // Initialize order pool with builder (CoralME pattern)
        ObjectBuilder<Order> orderBuilder = new ObjectBuilder<Order>() {
            @Override
            public Order newInstance() {
                return new Order();
            }
        };
        this.orderPool = new ArrayObjectPool<>(ORDER_POOL_INITIAL_SIZE, orderBuilder);

        // Initialize price level pool with builder
        ObjectBuilder<PriceLevel> priceLevelBuilder = new ObjectBuilder<PriceLevel>() {
            @Override
            public PriceLevel newInstance() {
                return new PriceLevel();
            }
        };
        this.priceLevelPool = new ArrayObjectPool<>(
            PRICE_LEVEL_POOL_INITIAL_SIZE, priceLevelBuilder);
    }

    /**
     * Add limit order to book
     */
    public Order addLimitOrder(long orderId, long clientId, Side side,
                              long price, long quantity, TimeInForce tif) {
        Order order = orderPool.get();
        order.init(orderId, clientId, symbol, side, OrderType.LIMIT,
                  price, quantity, tif, System.nanoTime());

        // Accept order first (CoralME pattern)
        order.accept();

        // Try to match
        match(order);

        // If not filled and not IOC, rest in book
        if (!order.isTerminal() && tif != TimeInForce.IOC) {
            restOrder(order);
        } else if (tif == TimeInForce.IOC && !order.isFilled()) {
            // IOC order not fully filled - cancel remaining
            order.cancel();
        }

        return order;
    }

    /**
     * Add market order to book
     */
    public Order addMarketOrder(long orderId, long clientId, Side side, long quantity) {
        Order order = orderPool.get();
        order.init(orderId, clientId, symbol, side, OrderType.MARKET,
                  0, quantity, TimeInForce.IOC, System.nanoTime());

        // Accept order first
        order.accept();

        // Match aggressively
        match(order);

        // Cancel any unfilled quantity (market orders don't rest)
        if (!order.isFilled()) {
            order.cancel();
        }

        return order;
    }

    /**
     * Cancel order by ID
     */
    public boolean cancelOrder(long orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return false;
        }

        // Mark as cancelled
        order.cancel();

        // Remove from book and return to pool
        removeOrder(order);
        order.reset();
        orderPool.release(order);
        return true;
    }

    /**
     * Match order against book (price-time priority)
     */
    private void match(Order order) {
        // Get opposite side (bids hit asks, asks hit bids)
        int oppositeSideIndex = order.getSide() == Side.BUY ? 1 : 0;

        // Walk through price levels
        OUTER:
        for (PriceLevel pl = head[oppositeSideIndex]; pl != null; pl = pl.next) {
            // Check price match
            if (order.getOrderType() != OrderType.MARKET) {
                if (!pl.canMatch(order.getSide(), order.getPrice())) {
                    break; // No more matches possible
                }
            }

            // Match against orders at this price level
            Order restingOrder = pl.getHead();
            while (restingOrder != null) {
                Order nextOrder = restingOrder.next; // Save next before modification

                // Skip trade-to-self if configured
                if (!allowTradeToSelf &&
                    restingOrder.getClientId() == order.getClientId()) {
                    restingOrder = nextOrder;
                    continue;
                }

                // Execute match
                long executeQty = Math.min(order.getRemainingQuantity(),
                                          restingOrder.getRemainingQuantity());
                long executePrice = restingOrder.getPrice(); // Price improvement for taker

                // Update order quantities
                order.execute(executeQty);
                restingOrder.execute(executeQty);

                // Update price level
                pl.onOrderExecuted(restingOrder, executeQty);

                // Track last executed price
                lastExecutedPrice = executePrice;

                // Generate execution IDs
                long execId = ++executionIdCounter;
                long matchId = ++matchIdCounter;

                // Remove resting order if filled
                if (restingOrder.isFilled()) {
                    orders.remove(restingOrder.getOrderId());
                    orderPool.release(restingOrder);
                }

                // Check if incoming order is filled
                if (order.isTerminal()) {
                    break OUTER;
                }

                restingOrder = nextOrder;
            }
        }
    }

    /**
     * Rest order in book (add to price level)
     */
    private void restOrder(Order order) {
        PriceLevel priceLevel = findOrCreatePriceLevel(order.getSide(), order.getPrice());
        priceLevel.addOrder(order);
        orders.put(order.getOrderId(), order);
        order.setAcceptTimestamp(System.nanoTime());
    }

    /**
     * Find existing price level or create new one
     */
    private PriceLevel findOrCreatePriceLevel(Side side, long price) {
        int sideIndex = side == Side.BUY ? 0 : 1;

        // Find insertion point
        PriceLevel foundLevel = null;
        for (PriceLevel pl = head[sideIndex]; pl != null; pl = pl.next) {
            if (side == Side.BUY && price >= pl.getPrice()) {
                foundLevel = pl;
                break;
            } else if (side == Side.SELL && price <= pl.getPrice()) {
                foundLevel = pl;
                break;
            }
        }

        // If exact price exists, return it
        if (foundLevel != null && foundLevel.getPrice() == price) {
            return foundLevel;
        }

        // Create new price level
        PriceLevel newLevel = priceLevelPool.get();
        newLevel.init(symbol, side, price);
        levelCount[sideIndex]++;

        // Insert at correct position
        if (foundLevel == null) {
            // Append at tail
            if (tail[sideIndex] != null) {
                tail[sideIndex].next = newLevel;
                newLevel.prev = tail[sideIndex];
            } else {
                head[sideIndex] = newLevel;
            }
            tail[sideIndex] = newLevel;
        } else {
            // Insert before foundLevel
            newLevel.next = foundLevel;
            newLevel.prev = foundLevel.prev;
            if (foundLevel.prev != null) {
                foundLevel.prev.next = newLevel;
            } else {
                head[sideIndex] = newLevel;
            }
            foundLevel.prev = newLevel;
        }

        return newLevel;
    }

    /**
     * Remove order from book
     */
    private void removeOrder(Order order) {
        PriceLevel priceLevel = order.getPriceLevel();
        if (priceLevel != null) {
            priceLevel.removeOrder(order);

            // Remove empty price level
            if (priceLevel.isEmpty()) {
                removePriceLevel(priceLevel);
            }
        }

        orders.remove(order.getOrderId());
    }

    /**
     * Remove empty price level from book
     */
    private void removePriceLevel(PriceLevel priceLevel) {
        Side side = priceLevel.getSide();
        int sideIndex = side == Side.BUY ? 0 : 1;

        // Update links
        if (priceLevel.prev != null) {
            priceLevel.prev.next = priceLevel.next;
        } else {
            head[sideIndex] = priceLevel.next;
        }

        if (priceLevel.next != null) {
            priceLevel.next.prev = priceLevel.prev;
        } else {
            tail[sideIndex] = priceLevel.prev;
        }

        levelCount[sideIndex]--;

        // Return to pool
        priceLevel.reset();
        priceLevelPool.release(priceLevel);
    }

    // Query methods

    public String getSymbol() { return symbol; }

    public boolean isEmpty() { return orders.isEmpty(); }

    public int getOrderCount() { return orders.size(); }

    public Order getOrder(long orderId) { return orders.get(orderId); }

    public boolean hasBids() { return head[0] != null; }

    public boolean hasAsks() { return head[1] != null; }

    public long getBestBidPrice() {
        return hasBids() ? head[0].getPrice() : 0;
    }

    public long getBestAskPrice() {
        return hasAsks() ? head[1].getPrice() : 0;
    }

    public long getBestBidSize() {
        return hasBids() ? head[0].getTotalQuantity() : 0;
    }

    public long getBestAskSize() {
        return hasAsks() ? head[1].getTotalQuantity() : 0;
    }

    public long getSpread() {
        if (!hasBids() || !hasAsks()) return 0;
        return getBestAskPrice() - getBestBidPrice();
    }

    public State getState() {
        if (!hasBids() && !hasAsks()) return State.EMPTY;
        if (!hasBids() || !hasAsks()) return State.ONESIDED;

        long spread = getSpread();
        if (spread == 0) return State.LOCKED;
        if (spread < 0) return State.CROSSED;
        return State.NORMAL;
    }

    public int getBidLevels() { return levelCount[0]; }

    public int getAskLevels() { return levelCount[1]; }

    public long getLastExecutedPrice() { return lastExecutedPrice; }

    public PriceLevel getBestBidLevel() { return head[0]; }

    public PriceLevel getBestAskLevel() { return head[1]; }

    @Override
    public String toString() {
        return "OrderBook{" + symbol + ", orders=" + orders.size() +
               ", state=" + getState() + "}";
    }
}

