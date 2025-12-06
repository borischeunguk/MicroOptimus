package com.microoptimus.exchange;

/**
 * Listener interface for exchange events
 */
public interface ExchangeListener {
    /**
     * Called when an order acknowledgment is received
     */
    void onOrderAck(ExchangeOrder order);

    /**
     * Called when an order is filled (partially or fully)
     */
    void onOrderFill(ExchangeOrder order, double fillPrice, long fillQuantity);

    /**
     * Called when an order is cancelled
     */
    void onOrderCancelled(ExchangeOrder order);

    /**
     * Called when an order is rejected
     */
    void onOrderRejected(ExchangeOrder order, String reason);

    /**
     * Called on connection status change
     */
    void onConnectionStatus(boolean connected);

    /**
     * Called on error
     */
    void onError(String error);
}
