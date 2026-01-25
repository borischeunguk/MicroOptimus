package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * InternalOrderBook - GC-free orderbook with object pooling
 *
 * Design principles:
 * - All objects (Order, PriceLevel) are pooled
 * - Intrusive linked lists avoid Node allocations
 * - LongMap for O(1) order lookup
 * - Price-time priority matching
 * - No allocations in hot path
 */
public class InternalOrderBook {

    // Symbol identification
    private final int symbolIndex;

    // Object pools
    private final OrderPool orderPool;
    private final PriceLevelPool priceLevelPool;

    // Order lookup by ID - O(1)
    private final Long2ObjectHashMap<Order> orderById;

    // Price level chains (sorted by price)
    // Bids: highest price first (descending)
    // Asks: lowest price first (ascending)
    private PriceLevel bidHead;
    private PriceLevel bidTail;
    private PriceLevel askHead;
    private PriceLevel askTail;

    // Price level lookup by price - O(1)
    private final Long2ObjectHashMap<PriceLevel> bidsByPrice;
    private final Long2ObjectHashMap<PriceLevel> asksByPrice;

    // Statistics
    private long totalOrders;
    private long totalMatches;
    private long totalCancels;
    private int bidLevelCount;
    private int askLevelCount;

    public InternalOrderBook(int symbolIndex) {
        this(symbolIndex, new OrderPool(), new PriceLevelPool());
    }

    public InternalOrderBook(int symbolIndex, OrderPool orderPool, PriceLevelPool priceLevelPool) {
        this.symbolIndex = symbolIndex;
        this.orderPool = orderPool;
        this.priceLevelPool = priceLevelPool;
        this.orderById = new Long2ObjectHashMap<>();
        this.bidsByPrice = new Long2ObjectHashMap<>();
        this.asksByPrice = new Long2ObjectHashMap<>();
        this.bidLevelCount = 0;
        this.askLevelCount = 0;
    }

    /**
     * Add order to the book - returns the order (may be partially or fully filled)
     */
    public Order addOrder(long orderId, long clientId, Side side, long price, long quantity,
                          com.microoptimus.common.types.TimeInForce tif, long timestamp) {
        Order order = orderPool.acquire();
        order.init(orderId, clientId, symbolIndex, side, com.microoptimus.common.types.OrderType.LIMIT,
                   price, quantity, tif, timestamp);

        orderById.put(orderId, order);
        totalOrders++;

        // Add to appropriate price level
        addToLevel(order);
        order.accept(timestamp);

        return order;
    }

    /**
     * Add order with flow type (for algo slices and principal orders)
     */
    public Order addOrder(long orderId, long clientId, long parentOrderId, Side side, long price,
                          long quantity, com.microoptimus.common.types.TimeInForce tif,
                          long timestamp, Order.OrderFlowType flowType) {
        Order order = orderPool.acquire();
        order.init(orderId, clientId, parentOrderId, symbolIndex, side,
                   com.microoptimus.common.types.OrderType.LIMIT, price, quantity, tif,
                   timestamp, flowType);

        orderById.put(orderId, order);
        totalOrders++;

        addToLevel(order);
        order.accept(timestamp);

        return order;
    }

    /**
     * Cancel an order by ID
     */
    public Order cancelOrder(long orderId, long timestamp) {
        Order order = orderById.get(orderId);
        if (order == null || order.isTerminal()) {
            return null;
        }

        // Remove from price level
        PriceLevel level = order.getPriceLevel();
        if (level != null) {
            level.removeOrder(order);
            if (level.isEmpty()) {
                removeLevel(level);
            }
        }

        order.cancel(timestamp);
        totalCancels++;

        return order;
    }

    /**
     * Get order by ID
     */
    public Order getOrder(long orderId) {
        return orderById.get(orderId);
    }

    /**
     * Remove order from book and return to pool
     */
    public void removeOrder(Order order) {
        if (order == null) return;

        // Remove from price level if still there
        PriceLevel level = order.getPriceLevel();
        if (level != null) {
            level.removeOrder(order);
            if (level.isEmpty()) {
                removeLevel(level);
            }
        }

        // Remove from lookup
        orderById.remove(order.getOrderId());

        // Return to pool
        orderPool.release(order);
    }

    /**
     * Get best bid price level
     */
    public PriceLevel getBestBid() {
        return bidHead;
    }

    /**
     * Get best ask price level
     */
    public PriceLevel getBestAsk() {
        return askHead;
    }

    /**
     * Get best bid price (0 if no bids)
     */
    public long getBestBidPrice() {
        return bidHead != null ? bidHead.getPrice() : 0;
    }

    /**
     * Get best ask price (0 if no asks)
     */
    public long getBestAskPrice() {
        return askHead != null ? askHead.getPrice() : Long.MAX_VALUE;
    }

    /**
     * Get spread (ask - bid)
     */
    public long getSpread() {
        if (bidHead == null || askHead == null) {
            return Long.MAX_VALUE;
        }
        return askHead.getPrice() - bidHead.getPrice();
    }

    /**
     * Check if book is crossed (bid >= ask)
     */
    public boolean isCrossed() {
        return bidHead != null && askHead != null &&
               bidHead.getPrice() >= askHead.getPrice();
    }

    /**
     * Add order to appropriate price level
     */
    private void addToLevel(Order order) {
        Side side = order.getSide();
        long price = order.getPrice();

        if (side == Side.BUY) {
            PriceLevel level = bidsByPrice.get(price);
            if (level == null) {
                level = createBidLevel(price);
            }
            level.addOrder(order);
        } else {
            PriceLevel level = asksByPrice.get(price);
            if (level == null) {
                level = createAskLevel(price);
            }
            level.addOrder(order);
        }
    }

    /**
     * Create a new bid price level and insert in sorted position
     */
    private PriceLevel createBidLevel(long price) {
        PriceLevel level = priceLevelPool.acquire();
        level.init(symbolIndex, Side.BUY, price);
        bidsByPrice.put(price, level);

        // Insert in descending price order (highest first)
        if (bidHead == null) {
            bidHead = bidTail = level;
        } else if (price > bidHead.getPrice()) {
            // Insert at head
            level.next = bidHead;
            bidHead.prev = level;
            bidHead = level;
        } else if (price < bidTail.getPrice()) {
            // Insert at tail
            level.prev = bidTail;
            bidTail.next = level;
            bidTail = level;
        } else {
            // Insert in middle
            PriceLevel current = bidHead;
            while (current.next != null && current.next.getPrice() > price) {
                current = current.next;
            }
            level.next = current.next;
            level.prev = current;
            if (current.next != null) {
                current.next.prev = level;
            }
            current.next = level;
        }

        bidLevelCount++;
        return level;
    }

    /**
     * Create a new ask price level and insert in sorted position
     */
    private PriceLevel createAskLevel(long price) {
        PriceLevel level = priceLevelPool.acquire();
        level.init(symbolIndex, Side.SELL, price);
        asksByPrice.put(price, level);

        // Insert in ascending price order (lowest first)
        if (askHead == null) {
            askHead = askTail = level;
        } else if (price < askHead.getPrice()) {
            // Insert at head
            level.next = askHead;
            askHead.prev = level;
            askHead = level;
        } else if (price > askTail.getPrice()) {
            // Insert at tail
            level.prev = askTail;
            askTail.next = level;
            askTail = level;
        } else {
            // Insert in middle
            PriceLevel current = askHead;
            while (current.next != null && current.next.getPrice() < price) {
                current = current.next;
            }
            level.next = current.next;
            level.prev = current;
            if (current.next != null) {
                current.next.prev = level;
            }
            current.next = level;
        }

        askLevelCount++;
        return level;
    }

    /**
     * Remove empty price level
     */
    private void removeLevel(PriceLevel level) {
        Side side = level.getSide();

        if (side == Side.BUY) {
            bidsByPrice.remove(level.getPrice());

            if (level.prev != null) {
                level.prev.next = level.next;
            } else {
                bidHead = level.next;
            }

            if (level.next != null) {
                level.next.prev = level.prev;
            } else {
                bidTail = level.prev;
            }

            bidLevelCount--;
        } else {
            asksByPrice.remove(level.getPrice());

            if (level.prev != null) {
                level.prev.next = level.next;
            } else {
                askHead = level.next;
            }

            if (level.next != null) {
                level.next.prev = level.prev;
            } else {
                askTail = level.prev;
            }

            askLevelCount--;
        }

        priceLevelPool.release(level);
    }

    /**
     * Execute quantity from best level (used by matching engine)
     */
    public void executeFromBestLevel(Side side, long quantity, long timestamp) {
        PriceLevel level = (side == Side.BUY) ? askHead : bidHead;
        if (level == null) return;

        long remaining = quantity;
        Order order = level.getHead();

        while (order != null && remaining > 0) {
            long execQty = order.execute(remaining, timestamp);
            level.onOrderExecuted(order, execQty);
            remaining -= execQty;

            Order next = order.next;
            if (order.isFilled()) {
                // Order will be removed by level.onOrderExecuted
                totalMatches++;
            }
            order = next;
        }

        if (level.isEmpty()) {
            removeLevel(level);
        }
    }

    // Statistics
    public int getSymbolIndex() { return symbolIndex; }
    public long getTotalOrders() { return totalOrders; }
    public long getTotalMatches() { return totalMatches; }
    public long getTotalCancels() { return totalCancels; }
    public int getBidLevelCount() { return bidLevelCount; }
    public int getAskLevelCount() { return askLevelCount; }
    public int getOrderCount() { return orderById.size(); }

    public long getBidQuantity() {
        long total = 0;
        PriceLevel level = bidHead;
        while (level != null) {
            total += level.getTotalQuantity();
            level = level.next;
        }
        return total;
    }

    public long getAskQuantity() {
        long total = 0;
        PriceLevel level = askHead;
        while (level != null) {
            total += level.getTotalQuantity();
            level = level.next;
        }
        return total;
    }

    @Override
    public String toString() {
        return String.format("InternalOrderBook[symbol=%d, bids=%d levels, asks=%d levels, orders=%d]",
                symbolIndex, bidLevelCount, askLevelCount, orderById.size());
    }
}
