package com.microoptimus.osm.sor;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;

/**
 * OrderRequest - Incoming order request for routing
 *
 * Pooled object for GC-free operation.
 */
public class OrderRequest {

    // Order identifiers
    public long sequenceId;
    public long orderId;
    public long clientId;
    public long parentOrderId;  // For algo slices

    // Symbol
    public int symbolIndex;
    public String symbol;  // Fallback for string symbol

    // Order attributes
    public Side side;
    public OrderType orderType;
    public long price;
    public long quantity;
    public TimeInForce timeInForce;

    // Flow classification
    public OrderFlowType flowType;

    // Timestamps
    public long timestamp;

    // Routing hints
    public long maxLatencyNanos;
    public long minFillQuantity;

    // Pool management
    private boolean inPool;

    public enum OrderFlowType {
        DMA,           // Direct Market Access
        PRINCIPAL,     // Principal/market-making
        ALGO_SLICE     // Algorithmic execution slice
    }

    public OrderRequest() {
        this.inPool = true;
        this.flowType = OrderFlowType.DMA;
    }

    /**
     * Initialize order request - called when acquiring from pool
     */
    public void init(long sequenceId, long orderId, long clientId, int symbolIndex,
                     Side side, OrderType orderType, long price, long quantity,
                     TimeInForce timeInForce, long timestamp) {
        this.sequenceId = sequenceId;
        this.orderId = orderId;
        this.clientId = clientId;
        this.parentOrderId = 0;
        this.symbolIndex = symbolIndex;
        this.symbol = null;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.timeInForce = timeInForce;
        this.flowType = OrderFlowType.DMA;
        this.timestamp = timestamp;
        this.maxLatencyNanos = 0;
        this.minFillQuantity = 0;
        this.inPool = false;
    }

    /**
     * Initialize with flow type (for algo slices and principal orders)
     */
    public void init(long sequenceId, long orderId, long clientId, long parentOrderId,
                     int symbolIndex, Side side, OrderType orderType, long price,
                     long quantity, TimeInForce timeInForce, long timestamp,
                     OrderFlowType flowType) {
        init(sequenceId, orderId, clientId, symbolIndex, side, orderType,
             price, quantity, timeInForce, timestamp);
        this.parentOrderId = parentOrderId;
        this.flowType = flowType;
    }

    /**
     * Reset for return to pool
     */
    public void reset() {
        this.sequenceId = 0;
        this.orderId = 0;
        this.clientId = 0;
        this.parentOrderId = 0;
        this.symbolIndex = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.quantity = 0;
        this.timeInForce = null;
        this.flowType = OrderFlowType.DMA;
        this.timestamp = 0;
        this.maxLatencyNanos = 0;
        this.minFillQuantity = 0;
        this.inPool = true;
    }

    public boolean isInPool() { return inPool; }

    public boolean isDMA() { return flowType == OrderFlowType.DMA; }
    public boolean isPrincipal() { return flowType == OrderFlowType.PRINCIPAL; }
    public boolean isAlgoSlice() { return flowType == OrderFlowType.ALGO_SLICE; }

    @Override
    public String toString() {
        return String.format("OrderRequest[id=%d, %s %s %d@%d, flow=%s]",
                orderId, side, symbol != null ? symbol : String.valueOf(symbolIndex),
                quantity, price, flowType);
    }
}
