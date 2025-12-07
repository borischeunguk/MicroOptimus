package com.microoptimus.signal;

import com.lmax.disruptor.RingBuffer;
import com.microoptimus.common.events.MarketDataEvent;
import com.microoptimus.common.events.OrderRequest;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MarketMakingStrategy - Simple market making strategy
 * Generates quotes based on market data
 */
public class MarketMakingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MarketMakingStrategy.class);

    private final RingBuffer<OrderRequest> orderRing;
    private final long quoteSize;
    private final long spreadTicks;
    private long orderIdCounter = 0;

    public MarketMakingStrategy(RingBuffer<OrderRequest> orderRing, long quoteSize, long spreadTicks) {
        this.orderRing = orderRing;
        this.quoteSize = quoteSize;
        this.spreadTicks = spreadTicks;
    }

    /**
     * Process market data and generate quotes
     */
    public void onMarketData(MarketDataEvent marketData) {
        if (marketData.getBidPrice() == 0 || marketData.getAskPrice() == 0) {
            return; // No valid market data
        }

        // Calculate mid price
        long midPrice = (marketData.getBidPrice() + marketData.getAskPrice()) / 2;

        // Calculate quote prices
        long bidPrice = midPrice - spreadTicks / 2;
        long askPrice = midPrice + spreadTicks / 2;

        // Generate bid order
        publishOrder(marketData.getSymbol(), Side.BUY, bidPrice, quoteSize);

        // Generate ask order
        publishOrder(marketData.getSymbol(), Side.SELL, askPrice, quoteSize);

        logger.debug("Generated quotes for {}: bid={}@{}, ask={}@{}",
                marketData.getSymbol(), quoteSize, bidPrice, quoteSize, askPrice);
    }

    private void publishOrder(String symbol, Side side, long price, long quantity) {
        long seq = orderRing.next();
        try {
            OrderRequest order = orderRing.get(seq);
            order.setSource(OrderRequest.Source.MARKET_MAKING);
            order.setOrderId(++orderIdCounter);
            order.setSymbol(symbol);
            order.setSide(side);
            order.setOrderType(OrderType.LIMIT);
            order.setPrice(price);
            order.setQuantity(quantity);
            order.setTif(TimeInForce.GTC);
            order.setTimestamp(System.nanoTime());
        } finally {
            orderRing.publish(seq);
        }
    }
}

