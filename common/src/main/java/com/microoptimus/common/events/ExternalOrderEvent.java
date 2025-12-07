package com.microoptimus.common.events;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * ExternalOrderEvent (RB-2: OSM → Liquidator)
 * Orders that need to be sent to CME exchange
 */
public class ExternalOrderEvent {

    public enum Action {
        NEW,
        CANCEL,
        MODIFY
    }

    private Action action;
    private long internalOrderId;
    private long cmeOrderId;  // For cancel/modify
    private String symbol;
    private Side side;
    private OrderType orderType;
    private long price;
    private long quantity;
    private TimeInForce tif;

    // CME-specific fields
    private String account;
    private String clearingAccount;
    private long timestamp;

    // Getters and setters
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }

    public long getInternalOrderId() { return internalOrderId; }
    public void setInternalOrderId(long internalOrderId) { this.internalOrderId = internalOrderId; }

    public long getCmeOrderId() { return cmeOrderId; }
    public void setCmeOrderId(long cmeOrderId) { this.cmeOrderId = cmeOrderId; }

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

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getClearingAccount() { return clearingAccount; }
    public void setClearingAccount(String clearingAccount) { this.clearingAccount = clearingAccount; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /**
     * Reset for object pooling (GC-free)
     */
    public void reset() {
        this.action = null;
        this.internalOrderId = 0;
        this.cmeOrderId = 0;
        this.symbol = null;
        this.side = null;
        this.orderType = null;
        this.price = 0;
        this.quantity = 0;
        this.tif = null;
        this.account = null;
        this.clearingAccount = null;
        this.timestamp = 0;
    }
}

