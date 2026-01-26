package com.microoptimus.internaliser;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

import java.util.function.Consumer;

/**
 * InternalMatchingEngine - Price-time priority matching engine
 *
 * Design principles:
 * - GC-free operation using pooled objects
 * - Price-time priority matching
 * - Supports IOC, GTC, DAY time-in-force
 * - Generates execution reports for both sides of a match
 */
public class InternalMatchingEngine {

    private final InternalOrderBook orderBook;
    private final ExecutionReportPool execReportPool;

    // Execution report callback
    private Consumer<ExecutionReport> execReportCallback;

    // ID generators
    private long nextExecId = 1;
    private long nextMatchId = 1;

    // Statistics
    private long matchCount;
    private long fillCount;
    private long partialFillCount;
    private long cancelCount;
    private long rejectCount;
    private long totalMatchedQuantity;
    private long totalMatchedValue; // price * quantity

    public InternalMatchingEngine(InternalOrderBook orderBook) {
        this(orderBook, new ExecutionReportPool());
    }

    public InternalMatchingEngine(InternalOrderBook orderBook, ExecutionReportPool execReportPool) {
        this.orderBook = orderBook;
        this.execReportPool = execReportPool;
    }

    /**
     * Set callback for execution reports
     */
    public void setExecReportCallback(Consumer<ExecutionReport> callback) {
        this.execReportCallback = callback;
    }

    /**
     * Process a new order - match aggressively then rest remainder
     */
    public MatchResult processOrder(long orderId, long clientId, Side side, long price,
                                    long quantity, TimeInForce tif, long timestamp) {
        return processOrder(orderId, clientId, 0, side, price, quantity, tif,
                           timestamp, Order.OrderFlowType.DMA);
    }

    /**
     * Process a new order with flow type (for algo slices and principal orders)
     */
    public MatchResult processOrder(long orderId, long clientId, long parentOrderId,
                                    Side side, long price, long quantity, TimeInForce tif,
                                    long timestamp, Order.OrderFlowType flowType) {

        MatchResult result = new MatchResult(orderId);
        long remainingQty = quantity;

        // Match against opposite side
        while (remainingQty > 0 && canMatch(side, price)) {
            PriceLevel bestLevel = (side == Side.BUY) ? orderBook.getBestAsk() : orderBook.getBestBid();
            if (bestLevel == null) break;

            // Check if we can match at this price level
            if (side == Side.BUY && bestLevel.getPrice() > price) break;
            if (side == Side.SELL && bestLevel.getPrice() < price) break;

            // Match against orders at this level
            Order passiveOrder = bestLevel.getHead();
            while (passiveOrder != null && remainingQty > 0) {
                long matchQty = Math.min(remainingQty, passiveOrder.getLeavesQuantity());
                long matchPrice = passiveOrder.getPrice();

                // Execute on passive order
                long prevLeaves = passiveOrder.getLeavesQuantity();
                passiveOrder.execute(matchQty, timestamp);
                bestLevel.onOrderExecuted(passiveOrder, matchQty);

                // Generate match ID for linking aggressive/passive fills
                long matchId = nextMatchId++;

                // Generate execution report for passive side
                ExecutionReport passiveExec = execReportPool.acquire();
                passiveExec.initTrade(nextExecId++, matchId, passiveOrder.getOrderId(),
                        passiveOrder.getSymbolIndex(), passiveOrder.getSide(),
                        passiveOrder.getPrice(), matchPrice, matchQty,
                        passiveOrder.getLeavesQuantity(), passiveOrder.getExecutedQuantity(),
                        orderId, clientId, timestamp);
                publishExecReport(passiveExec);

                // Update result for aggressive side
                result.addFill(matchPrice, matchQty, passiveOrder.getOrderId(),
                              passiveOrder.getClientId());

                remainingQty -= matchQty;
                matchCount++;
                totalMatchedQuantity += matchQty;
                totalMatchedValue += matchPrice * matchQty;

                // Track fill type
                if (passiveOrder.isFilled()) {
                    fillCount++;
                    // Remove filled order from book and return to pool
                    orderBook.removeOrder(passiveOrder);
                } else {
                    partialFillCount++;
                }

                passiveOrder = bestLevel.getHead(); // Get next order (previous was removed or still head)
            }

            // Remove empty level
            if (bestLevel.isEmpty()) {
                // Level will be removed by orderBook in onOrderExecuted
            }
        }

        result.setExecutedQuantity(quantity - remainingQty);
        result.setLeavesQuantity(remainingQty);

        // Handle remaining quantity based on TIF
        if (remainingQty > 0) {
            if (tif == TimeInForce.IOC) {
                // IOC: Cancel remaining
                result.setStatus(MatchResult.Status.PARTIAL_FILL_CANCELLED);
                cancelCount++;
            } else {
                // GTC/DAY: Rest in book
                Order restingOrder = orderBook.addOrder(orderId, clientId, parentOrderId,
                        side, price, remainingQty, tif, timestamp, flowType);
                result.setRestingOrder(restingOrder);
                result.setStatus(remainingQty == quantity ?
                        MatchResult.Status.RESTING : MatchResult.Status.PARTIAL_FILL_RESTING);
            }
        } else {
            result.setStatus(MatchResult.Status.FILLED);
            fillCount++;
        }

        // Generate execution report for aggressive order
        ExecutionReport aggrExec = execReportPool.acquire();
        if (result.getExecutedQuantity() > 0) {
            aggrExec.initTrade(nextExecId++, nextMatchId - 1, orderId,
                    orderBook.getSymbolIndex(), side, price,
                    result.getAvgFillPrice(), result.getExecutedQuantity(),
                    result.getLeavesQuantity(), result.getExecutedQuantity(),
                    0, 0, timestamp);
        } else {
            aggrExec.initStatus(nextExecId++, orderId, orderBook.getSymbolIndex(), side, price,
                    ExecutionReport.ExecType.NEW, remainingQty, 0, timestamp);
        }
        publishExecReport(aggrExec);

        return result;
    }

    /**
     * Cancel an order
     */
    public CancelResult cancelOrder(long orderId, long timestamp) {
        Order order = orderBook.cancelOrder(orderId, timestamp);
        if (order == null) {
            return new CancelResult(orderId, CancelResult.Status.NOT_FOUND, 0);
        }

        // Calculate cancelled quantity (leavesQuantity is set to 0 by cancel())
        long cancelledQty = order.getOriginalQuantity() - order.getExecutedQuantity();
        cancelCount++;

        // Generate execution report
        ExecutionReport cancelExec = execReportPool.acquire();
        cancelExec.initStatus(nextExecId++, orderId, order.getSymbolIndex(), order.getSide(),
                order.getPrice(), ExecutionReport.ExecType.CANCELLED,
                0, order.getExecutedQuantity(), timestamp);
        publishExecReport(cancelExec);

        // Return order to pool
        orderBook.removeOrder(order);

        return new CancelResult(orderId, CancelResult.Status.CANCELLED, cancelledQty);
    }

    /**
     * Check if incoming order can match
     */
    private boolean canMatch(Side side, long price) {
        if (side == Side.BUY) {
            PriceLevel bestAsk = orderBook.getBestAsk();
            return bestAsk != null && price >= bestAsk.getPrice();
        } else {
            PriceLevel bestBid = orderBook.getBestBid();
            return bestBid != null && price <= bestBid.getPrice();
        }
    }

    /**
     * Publish execution report to callback
     */
    private void publishExecReport(ExecutionReport report) {
        if (execReportCallback != null) {
            execReportCallback.accept(report);
        }
        // Note: Caller is responsible for releasing report back to pool
    }

    /**
     * Release execution report back to pool (called by consumer)
     */
    public void releaseExecReport(ExecutionReport report) {
        execReportPool.release(report);
    }

    // Statistics
    public long getMatchCount() { return matchCount; }
    public long getFillCount() { return fillCount; }
    public long getPartialFillCount() { return partialFillCount; }
    public long getCancelCount() { return cancelCount; }
    public long getRejectCount() { return rejectCount; }
    public long getTotalMatchedQuantity() { return totalMatchedQuantity; }
    public long getTotalMatchedValue() { return totalMatchedValue; }

    public InternalOrderBook getOrderBook() { return orderBook; }

    /**
     * Match result for aggressive order
     */
    public static class MatchResult {
        public enum Status {
            FILLED,                  // Fully filled
            PARTIAL_FILL_RESTING,    // Partial fill, remainder resting
            PARTIAL_FILL_CANCELLED,  // Partial fill, remainder cancelled (IOC)
            RESTING,                 // No fill, resting in book
            REJECTED                 // Order rejected
        }

        private final long orderId;
        private Status status;
        private long executedQuantity;
        private long leavesQuantity;
        private Order restingOrder;

        // Fill details (simplified - could use pooled array)
        private long totalValue;
        private int fillCount;

        public MatchResult(long orderId) {
            this.orderId = orderId;
            this.status = Status.RESTING;
        }

        public void addFill(long price, long quantity, long counterOrderId, long counterClientId) {
            totalValue += price * quantity;
            fillCount++;
        }

        public long getAvgFillPrice() {
            return executedQuantity > 0 ? totalValue / executedQuantity : 0;
        }

        // Setters
        void setStatus(Status status) { this.status = status; }
        void setExecutedQuantity(long qty) { this.executedQuantity = qty; }
        void setLeavesQuantity(long qty) { this.leavesQuantity = qty; }
        void setRestingOrder(Order order) { this.restingOrder = order; }

        // Getters
        public long getOrderId() { return orderId; }
        public Status getStatus() { return status; }
        public long getExecutedQuantity() { return executedQuantity; }
        public long getLeavesQuantity() { return leavesQuantity; }
        public Order getRestingOrder() { return restingOrder; }
        public int getFillCount() { return fillCount; }

        public boolean isFilled() { return status == Status.FILLED; }
        public boolean isResting() { return status == Status.RESTING || status == Status.PARTIAL_FILL_RESTING; }
    }

    /**
     * Cancel result
     */
    public static class CancelResult {
        public enum Status {
            CANCELLED,
            NOT_FOUND,
            ALREADY_FILLED,
            REJECTED
        }

        private final long orderId;
        private final Status status;
        private final long cancelledQuantity;

        public CancelResult(long orderId, Status status, long cancelledQuantity) {
            this.orderId = orderId;
            this.status = status;
            this.cancelledQuantity = cancelledQuantity;
        }

        public long getOrderId() { return orderId; }
        public Status getStatus() { return status; }
        public long getCancelledQuantity() { return cancelledQuantity; }
        public boolean isSuccess() { return status == Status.CANCELLED; }
    }
}
