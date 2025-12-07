package com.microoptimus.app;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import com.microoptimus.common.events.*;
import com.microoptimus.gateway.CMEMarketDataGateway;
import com.microoptimus.liquidator.CMEOrderGateway;
import com.microoptimus.osm.OrderStateManager;
import com.microoptimus.recombinor.MarketDataProcessor;
import com.microoptimus.signal.MarketMakingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MicroOptimusApp - Main application
 * Wires all modules together using LMAX Disruptor
 */
public class MicroOptimusApp {

    private static final Logger logger = LoggerFactory.getLogger(MicroOptimusApp.class);

    // Ring buffer sizes (must be power of 2)
    private static final int ORDER_BUFFER_SIZE = 8192;
    private static final int EXECUTION_BUFFER_SIZE = 4096;
    private static final int MARKET_DATA_BUFFER_SIZE = 4096;

    private Disruptor<OrderRequest> orderDisruptor;
    private Disruptor<ExternalOrderEvent> externalOrderDisruptor;
    private Disruptor<ExternalExecutionEvent> externalExecutionDisruptor;
    private Disruptor<InternalExecutionEvent> internalExecutionDisruptor;
    private Disruptor<MarketDataEvent> marketDataDisruptor;
    private Disruptor<ExternalMarketDataEvent> externalMarketDataDisruptor;

    private CMEMarketDataGateway marketDataGateway;

    public static void main(String[] args) {
        MicroOptimusApp app = new MicroOptimusApp();

        try {
            app.start();

            logger.info("MicroOptimus started. Press Ctrl+C to stop.");

            // Keep running
            Thread.currentThread().join();

        } catch (Exception e) {
            logger.error("Error running MicroOptimus", e);
        } finally {
            app.shutdown();
        }
    }

    public void start() {
        logger.info("Starting MicroOptimus MVP...");

        // Create all disruptors
        createDisruptors();

        // Wire up event handlers
        wireHandlers();

        // Start all disruptors
        startDisruptors();

        logger.info("MicroOptimus MVP started successfully");
    }

    private void createDisruptors() {
        // RB-1: OrderRequest (OSM-Signal → OSM)
        orderDisruptor = new Disruptor<>(
            OrderRequest::new,
            ORDER_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );

        // RB-2: ExternalOrderEvent (OSM → Liquidator)
        externalOrderDisruptor = new Disruptor<>(
            ExternalOrderEvent::new,
            ORDER_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );

        // RB-3: ExternalExecutionEvent (Liquidator → OSM)
        externalExecutionDisruptor = new Disruptor<>(
            ExternalExecutionEvent::new,
            EXECUTION_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );

        // RB-4: InternalExecutionEvent (OSM → Recombinor)
        internalExecutionDisruptor = new Disruptor<>(
            InternalExecutionEvent::new,
            EXECUTION_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );

        // RB-5: MarketDataEvent (Recombinor → OSM-Signal)
        marketDataDisruptor = new Disruptor<>(
            MarketDataEvent::new,
            MARKET_DATA_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );

        // RB-6: ExternalMarketDataEvent (Gateway → Recombinor)
        externalMarketDataDisruptor = new Disruptor<>(
            ExternalMarketDataEvent::new,
            MARKET_DATA_BUFFER_SIZE,
            DaemonThreadFactory.INSTANCE,
            ProducerType.SINGLE,
            new BusySpinWaitStrategy()
        );
    }

    private void wireHandlers() {
        // Create module instances
        OrderStateManager osm = new OrderStateManager("ESH5");
        CMEOrderGateway liquidator = new CMEOrderGateway(
            externalExecutionDisruptor.getRingBuffer()
        );
        MarketDataProcessor recombinor = new MarketDataProcessor(
            marketDataDisruptor.getRingBuffer()
        );
        MarketMakingStrategy strategy = new MarketMakingStrategy(
            orderDisruptor.getRingBuffer(),
            100, // quote size
            2    // spread ticks
        );

        // Wire: OSM-Signal → OSM (RB-1)
        orderDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            logger.debug("OSM received order: {}", event.getOrderId());
            // TODO: OSM processing
        });

        // Wire: OSM → Liquidator (RB-2)
        externalOrderDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            liquidator.sendOrder(event);
        });

        // Wire: Liquidator → OSM (RB-3)
        externalExecutionDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            logger.debug("OSM received CME execution: {}", event.getCmeOrderId());
            // TODO: OSM update from CME fill
        });

        // Wire: OSM → Recombinor (RB-4)
        internalExecutionDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            recombinor.onInternalExecution(event);
        });

        // Wire: Recombinor → OSM-Signal (RB-5)
        marketDataDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            strategy.onMarketData(event);
        });

        // Wire: Gateway → Recombinor (RB-6)
        externalMarketDataDisruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            recombinor.onExternalMarketData(event);
        });

        // Start market data gateway
        marketDataGateway = new CMEMarketDataGateway(
            externalMarketDataDisruptor.getRingBuffer()
        );
        new Thread(marketDataGateway, "MD-Gateway").start();
    }

    private void startDisruptors() {
        orderDisruptor.start();
        externalOrderDisruptor.start();
        externalExecutionDisruptor.start();
        internalExecutionDisruptor.start();
        marketDataDisruptor.start();
        externalMarketDataDisruptor.start();
    }

    public void shutdown() {
        logger.info("Shutting down MicroOptimus...");

        if (marketDataGateway != null) {
            marketDataGateway.stop();
        }

        if (orderDisruptor != null) orderDisruptor.shutdown();
        if (externalOrderDisruptor != null) externalOrderDisruptor.shutdown();
        if (externalExecutionDisruptor != null) externalExecutionDisruptor.shutdown();
        if (internalExecutionDisruptor != null) internalExecutionDisruptor.shutdown();
        if (marketDataDisruptor != null) marketDataDisruptor.shutdown();
        if (externalMarketDataDisruptor != null) externalMarketDataDisruptor.shutdown();

        logger.info("MicroOptimus shutdown complete");
    }
}

