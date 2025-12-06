package com.microoptimus.gateway;

import com.microoptimus.exchange.*;
import com.microoptimus.marketdata.*;

import java.util.logging.Logger;

/**
 * Order gateway that bridges strategies with exchange and orderbook
 */
public class OrderGateway implements ExchangeListener, MarketDataListener {
    private static final Logger logger = Logger.getLogger(OrderGateway.class.getName());

    private final ExchangeSession exchangeSession;
    private final MarketDataFeed marketDataFeed;

    public OrderGateway(ExchangeSession exchangeSession, MarketDataFeed marketDataFeed) {
        this.exchangeSession = exchangeSession;
        this.marketDataFeed = marketDataFeed;

        // Register as listener
        exchangeSession.addListener(this);
        marketDataFeed.addListener(this);
    }

    /**
     * Submit a buy order
     */
    public String buyLimit(String symbol, double price, long quantity) {
        logger.info(String.format("Submitting BUY LIMIT %s: %d @ %.2f", symbol, quantity, price));
        return exchangeSession.submitOrder(symbol, ExchangeOrder.Side.BUY,
                ExchangeOrder.Type.LIMIT, price, quantity);
    }

    /**
     * Submit a sell order
     */
    public String sellLimit(String symbol, double price, long quantity) {
        logger.info(String.format("Submitting SELL LIMIT %s: %d @ %.2f", symbol, quantity, price));
        return exchangeSession.submitOrder(symbol, ExchangeOrder.Side.SELL,
                ExchangeOrder.Type.LIMIT, price, quantity);
    }

    /**
     * Submit a market buy order
     */
    public String buyMarket(String symbol, long quantity) {
        logger.info(String.format("Submitting BUY MARKET %s: %d", symbol, quantity));
        return exchangeSession.submitOrder(symbol, ExchangeOrder.Side.BUY,
                ExchangeOrder.Type.MARKET, 0.0, quantity);
    }

    /**
     * Submit a market sell order
     */
    public String sellMarket(String symbol, long quantity) {
        logger.info(String.format("Submitting SELL MARKET %s: %d", symbol, quantity));
        return exchangeSession.submitOrder(symbol, ExchangeOrder.Side.SELL,
                ExchangeOrder.Type.MARKET, 0.0, quantity);
    }

    /**
     * Cancel an order
     */
    public boolean cancel(String orderId) {
        logger.info("Cancelling order: " + orderId);
        return exchangeSession.cancelOrder(orderId);
    }

    /**
     * Get order status
     */
    public ExchangeOrder getOrderStatus(String orderId) {
        return exchangeSession.getOrder(orderId);
    }

    /**
     * Get market data for a symbol
     */
    public MarketData getMarketData(String symbol) {
        return marketDataFeed.getLatestData(symbol);
    }

    // ExchangeListener implementation
    @Override
    public void onOrderAck(ExchangeOrder order) {
        logger.info("Order ACK: " + order);
    }

    @Override
    public void onOrderFill(ExchangeOrder order, double fillPrice, long fillQuantity) {
        logger.info(String.format("Order FILL: %s - %d @ %.2f", order.getOrderId(), fillQuantity, fillPrice));
    }

    @Override
    public void onOrderCancelled(ExchangeOrder order) {
        logger.info("Order CANCELLED: " + order);
    }

    @Override
    public void onOrderRejected(ExchangeOrder order, String reason) {
        logger.warning("Order REJECTED: " + order + " - Reason: " + reason);
    }

    @Override
    public void onConnectionStatus(boolean connected) {
        logger.info("Exchange connection status: " + (connected ? "CONNECTED" : "DISCONNECTED"));
    }

    @Override
    public void onError(String error) {
        logger.severe("Exchange error: " + error);
    }

    // MarketDataListener implementation
    @Override
    public void onMarketData(MarketData marketData) {
        // Can be used for logging or strategy updates
    }

    @Override
    public void onTrade(String symbol, double price, long quantity, String tradeId) {
        logger.info(String.format("Market trade: %s - %d @ %.2f [%s]", symbol, quantity, price, tradeId));
    }
}
