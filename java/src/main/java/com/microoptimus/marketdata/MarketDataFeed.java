package com.microoptimus.marketdata;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Market data feed handler that manages subscriptions and distributes market data
 */
public class MarketDataFeed {
    private static final Logger logger = Logger.getLogger(MarketDataFeed.class.getName());

    private final Map<String, MarketData> latestData;
    private final List<MarketDataListener> listeners;
    private final ScheduledExecutorService scheduler;
    private volatile boolean connected;

    public MarketDataFeed() {
        this.latestData = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.connected = false;
    }

    /**
     * Connect to market data source
     */
    public void connect() {
        logger.info("Connecting to market data feed...");
        connected = true;
        notifyConnectionStatus(true);
        logger.info("Connected to market data feed");
    }

    /**
     * Disconnect from market data source
     */
    public void disconnect() {
        logger.info("Disconnecting from market data feed...");
        connected = false;
        notifyConnectionStatus(false);
        scheduler.shutdown();
        logger.info("Disconnected from market data feed");
    }

    /**
     * Subscribe to market data for a symbol
     */
    public void subscribe(String symbol) {
        if (!connected) {
            logger.warning("Cannot subscribe - not connected");
            return;
        }
        logger.info("Subscribed to " + symbol);
    }

    /**
     * Unsubscribe from market data for a symbol
     */
    public void unsubscribe(String symbol) {
        logger.info("Unsubscribed from " + symbol);
        latestData.remove(symbol);
    }

    /**
     * Add a market data listener
     */
    public void addListener(MarketDataListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a market data listener
     */
    public void removeListener(MarketDataListener listener) {
        listeners.remove(listener);
    }

    /**
     * Update market data (called by feed connector)
     */
    public void updateMarketData(MarketData data) {
        latestData.put(data.getSymbol(), data);
        notifyMarketData(data);
    }

    /**
     * Get latest market data for a symbol
     */
    public MarketData getLatestData(String symbol) {
        return latestData.get(symbol);
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected;
    }

    private void notifyMarketData(MarketData data) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onMarketData(data);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyTrade(String symbol, double price, long quantity, String tradeId) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onTrade(symbol, price, quantity, tradeId);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyConnectionStatus(boolean connected) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onConnectionStatus(connected);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    /**
     * Simulate market data updates (for testing)
     */
    public void startSimulation(String symbol, double basePrice) {
        Random random = new Random();
        scheduler.scheduleAtFixedRate(() -> {
            if (!connected) return;

            double bidPrice = basePrice + (random.nextDouble() - 0.5) * 2;
            double askPrice = bidPrice + 0.01 + random.nextDouble() * 0.05;
            long bidSize = 100 + random.nextInt(900);
            long askSize = 100 + random.nextInt(900);
            double lastPrice = (bidPrice + askPrice) / 2;
            long lastSize = 50 + random.nextInt(200);

            MarketData data = new MarketData(symbol, bidPrice, bidSize, askPrice, askSize, lastPrice, lastSize);
            updateMarketData(data);
        }, 0, 100, TimeUnit.MILLISECONDS);
    }
}
