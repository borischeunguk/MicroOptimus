package com.microoptimus.marketdata;

/**
 * Listener interface for market data updates
 */
public interface MarketDataListener {
    /**
     * Called when new market data is received
     */
    void onMarketData(MarketData marketData);

    /**
     * Called when a trade is received
     */
    void onTrade(String symbol, double price, long quantity, String tradeId);

    /**
     * Called on connection status change
     */
    void onConnectionStatus(boolean connected);

    /**
     * Called on error
     */
    void onError(String error);
}
