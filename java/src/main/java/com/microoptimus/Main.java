package com.microoptimus;

import com.microoptimus.exchange.*;
import com.microoptimus.marketdata.*;
import com.microoptimus.gateway.*;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Main demo application for MicroOptimus Java components
 */
public class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws InterruptedException {
        logger.info("=== MicroOptimus Java Components Demo ===\n");

        // Create components
        ExchangeSession exchangeSession = new ExchangeSession("MAIN_SESSION");
        MarketDataFeed marketDataFeed = new MarketDataFeed();

        // Create order gateway
        OrderGateway gateway = new OrderGateway(exchangeSession, marketDataFeed);

        logger.info("=== Scenario 1: Connecting to Services ===");

        // Connect to exchange and market data
        exchangeSession.connect();
        marketDataFeed.connect();

        // Subscribe to market data
        String symbol = "BTCUSD";
        marketDataFeed.subscribe(symbol);

        logger.info("\n=== Scenario 2: Starting Market Data Simulation ===");

        // Start market data simulation
        marketDataFeed.startSimulation(symbol, 50000.0);

        // Add listener for market data
        marketDataFeed.addListener(new MarketDataListener() {
            @Override
            public void onMarketData(MarketData marketData) {
                logger.info("Market Data: " + marketData);
            }

            @Override
            public void onTrade(String symbol, double price, long quantity, String tradeId) {
                logger.info(String.format("Trade: %s %d @ %.2f [%s]", symbol, quantity, price, tradeId));
            }

            @Override
            public void onConnectionStatus(boolean connected) {
                logger.info("Market data connection: " + (connected ? "CONNECTED" : "DISCONNECTED"));
            }

            @Override
            public void onError(String error) {
                logger.severe("Market data error: " + error);
            }
        });

        // Wait for some market data
        TimeUnit.SECONDS.sleep(2);

        logger.info("\n=== Scenario 3: Submitting Orders ===");

        // Get current market data
        MarketData currentData = gateway.getMarketData(symbol);
        if (currentData != null) {
            logger.info("Current market: " + currentData);

            // Submit buy order below bid
            double bidPrice = currentData.getBidPrice() - 5.0;
            String buyOrderId = gateway.buyLimit(symbol, bidPrice, 5);
            logger.info("Submitted buy order: " + buyOrderId);

            // Submit sell order above ask
            double askPrice = currentData.getAskPrice() + 5.0;
            String sellOrderId = gateway.sellLimit(symbol, askPrice, 5);
            logger.info("Submitted sell order: " + sellOrderId);

            // Wait a bit
            TimeUnit.SECONDS.sleep(2);

            // Simulate fills
            logger.info("\n=== Scenario 4: Simulating Order Fills ===");
            exchangeSession.simulateFill(buyOrderId, bidPrice, 5);
            exchangeSession.simulateFill(sellOrderId, askPrice, 5);

            // Check order status
            TimeUnit.SECONDS.sleep(1);
            ExchangeOrder buyOrder = gateway.getOrderStatus(buyOrderId);
            ExchangeOrder sellOrder = gateway.getOrderStatus(sellOrderId);

            logger.info("Buy order status: " + buyOrder);
            logger.info("Sell order status: " + sellOrder);
        }

        // Run for a bit longer
        logger.info("\n=== Monitoring Market Data ===");
        TimeUnit.SECONDS.sleep(3);

        // Cleanup
        logger.info("\n=== Shutting Down ===");
        marketDataFeed.disconnect();
        exchangeSession.disconnect();

        logger.info("=== Demo Complete ===");
    }
}
