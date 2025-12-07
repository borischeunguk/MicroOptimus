package com.microoptimus.common.events;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * OrderRequest event (RB-1: OSM-Signal → OSM)
 * Strategy-generated orders/quotes
 */
public class OrderRequest {

    public enum Source {
        MARKET_MAKING,
        RISK_MANAGEMENT,
        MANUAL
    }

    private Source source;
    private long orderId;
    private String symbol;
    private Side side;
    private OrderType orderType;
    private long price;  // In ticks to avoid double precision issues
    private long quantity;
    private TimeInForce tif;
    private long timestamp;

    // Getters and setters
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public long getOrderId() { return orderId; }
    public void setOrderId(long orderId) { this.orderId = orderId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }

    public OrderType getOrderType() { return orderType; }
    public void setOrderType(OrderType orderType) { this.orderType = orderType; }

    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }

    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }

    public TimeInForce getTif() { return tif; }
    public void setTif(TimeInForce tif) { this.tif = tif; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.source = null;
        this.orderId = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.quantity = 0;
        this.tif = null;
        this.timestamp = 0;
    }

    @Override
    public String toString() {
        return "OrderRequest{" +
                "source=" + source +
                ", orderId=" + orderId +
                ", symbol='" + symbol + '\'' +
                ", side=" + side +
                ", orderType=" + orderType +
                ", price=" + price +
                ", quantity=" + quantity +
                ", tif=" + tif +
                ", timestamp=" + timestamp +
                '}';
    }
}

