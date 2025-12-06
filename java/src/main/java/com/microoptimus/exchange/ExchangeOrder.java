package com.microoptimus.exchange;

import java.time.Instant;

/**
 * Order representation for exchange communication
 */
public class ExchangeOrder {
    public enum Side { BUY, SELL }
    public enum Type { LIMIT, MARKET, IOC, FOK }
    public enum Status { NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

    private final String orderId;
    private final String clientOrderId;
    private final String symbol;
    private final Side side;
    private final Type type;
    private Status status;
    private final double price;
    private final long quantity;
    private long filledQuantity;
    private final Instant timestamp;
    private String rejectReason;

    public ExchangeOrder(String orderId, String clientOrderId, String symbol,
                         Side side, Type type, double price, long quantity) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.status = Status.NEW;
        this.price = price;
        this.quantity = quantity;
        this.filledQuantity = 0;
        this.timestamp = Instant.now();
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public double getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getFilledQuantity() { return filledQuantity; }
    public long getRemainingQuantity() { return quantity - filledQuantity; }
    public Instant getTimestamp() { return timestamp; }
    public String getRejectReason() { return rejectReason; }

    // Setters
    public void setStatus(Status status) { this.status = status; }
    public void setFilledQuantity(long filledQuantity) { this.filledQuantity = filledQuantity; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public boolean isComplete() {
        return status == Status.FILLED || status == Status.CANCELLED || status == Status.REJECTED;
    }

    @Override
    public String toString() {
        return String.format("Order[%s] %s %s %d@%.2f %s (filled: %d)",
                orderId, side, symbol, quantity, price, status, filledQuantity);
    }
}
