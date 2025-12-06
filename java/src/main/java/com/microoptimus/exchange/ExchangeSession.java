package com.microoptimus.exchange;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Exchange session manager - handles order routing and state management
 */
public class ExchangeSession {
    private static final Logger logger = Logger.getLogger(ExchangeSession.class.getName());

    private final String sessionId;
    private final Map<String, ExchangeOrder> orders;
    private final List<ExchangeListener> listeners;
    private volatile boolean connected;
    private long nextOrderId;

    public ExchangeSession(String sessionId) {
        this.sessionId = sessionId;
        this.orders = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.connected = false;
        this.nextOrderId = 1;
    }

    /**
     * Connect to exchange
     */
    public void connect() {
        logger.info("Connecting to exchange session: " + sessionId);
        connected = true;
        notifyConnectionStatus(true);
        logger.info("Connected to exchange");
    }

    /**
     * Disconnect from exchange
     */
    public void disconnect() {
        logger.info("Disconnecting from exchange");
        connected = false;
        notifyConnectionStatus(false);
        logger.info("Disconnected from exchange");
    }

    /**
     * Submit a new order to the exchange
     */
    public String submitOrder(String symbol, ExchangeOrder.Side side,
                             ExchangeOrder.Type type, double price, long quantity) {
        if (!connected) {
            logger.warning("Cannot submit order - not connected");
            return null;
        }

        String orderId = generateOrderId();
        String clientOrderId = "CLIENT_" + orderId;

        ExchangeOrder order = new ExchangeOrder(orderId, clientOrderId, symbol, side, type, price, quantity);
        orders.put(orderId, order);

        logger.info("Submitting order: " + order);
        
        // Simulate order acknowledgment
        notifyOrderAck(order);

        return orderId;
    }

    /**
     * Cancel an order
     */
    public boolean cancelOrder(String orderId) {
        if (!connected) {
            logger.warning("Cannot cancel order - not connected");
            return false;
        }

        ExchangeOrder order = orders.get(orderId);
        if (order == null) {
            logger.warning("Order not found: " + orderId);
            return false;
        }

        if (order.isComplete()) {
            logger.warning("Cannot cancel completed order: " + orderId);
            return false;
        }

        order.setStatus(ExchangeOrder.Status.CANCELLED);
        logger.info("Cancelled order: " + orderId);
        notifyOrderCancelled(order);

        return true;
    }

    /**
     * Get order by ID
     */
    public ExchangeOrder getOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * Get all orders
     */
    public Collection<ExchangeOrder> getAllOrders() {
        return new ArrayList<>(orders.values());
    }

    /**
     * Add exchange listener
     */
    public void addListener(ExchangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove exchange listener
     */
    public void removeListener(ExchangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Simulate order fill (for testing)
     */
    public void simulateFill(String orderId, double fillPrice, long fillQuantity) {
        ExchangeOrder order = orders.get(orderId);
        if (order == null) {
            logger.warning("Order not found: " + orderId);
            return;
        }

        long newFilled = order.getFilledQuantity() + fillQuantity;
        order.setFilledQuantity(newFilled);

        if (newFilled >= order.getQuantity()) {
            order.setStatus(ExchangeOrder.Status.FILLED);
        } else {
            order.setStatus(ExchangeOrder.Status.PARTIALLY_FILLED);
        }

        notifyOrderFill(order, fillPrice, fillQuantity);
    }

    private String generateOrderId() {
        return "ORD_" + System.currentTimeMillis() + "_" + (nextOrderId++);
    }

    private void notifyOrderAck(ExchangeOrder order) {
        for (ExchangeListener listener : listeners) {
            try {
                listener.onOrderAck(order);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyOrderFill(ExchangeOrder order, double fillPrice, long fillQuantity) {
        for (ExchangeListener listener : listeners) {
            try {
                listener.onOrderFill(order, fillPrice, fillQuantity);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyOrderCancelled(ExchangeOrder order) {
        for (ExchangeListener listener : listeners) {
            try {
                listener.onOrderCancelled(order);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyOrderRejected(ExchangeOrder order, String reason) {
        for (ExchangeListener listener : listeners) {
            try {
                listener.onOrderRejected(order, reason);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }

    private void notifyConnectionStatus(boolean connected) {
        for (ExchangeListener listener : listeners) {
            try {
                listener.onConnectionStatus(connected);
            } catch (Exception e) {
                logger.severe("Error notifying listener: " + e.getMessage());
            }
        }
    }
}
