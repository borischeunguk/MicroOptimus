package com.microoptimus.gateway;

import com.lmax.disruptor.RingBuffer;
import com.microoptimus.common.events.ExternalMarketDataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CMEMarketDataGateway - Receives CME market data feed
 * Decodes SBE/FIX messages and publishes to ring buffer
 */
public class CMEMarketDataGateway implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CMEMarketDataGateway.class);

    private final RingBuffer<ExternalMarketDataEvent> marketDataRing;
    private volatile boolean running = true;

    public CMEMarketDataGateway(RingBuffer<ExternalMarketDataEvent> marketDataRing) {
        this.marketDataRing = marketDataRing;
    }

    @Override
    public void run() {
        logger.info("CME Market Data Gateway started");

        while (running) {
            try {
                // TODO: Receive UDP multicast packets from CME
                // TODO: Decode SBE/FIX messages
                // TODO: Handle gap detection and retransmission

                // Simulate receiving market data
                receiveMarketData();

                Thread.sleep(100); // Temporary - remove in production

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error receiving market data", e);
            }
        }

        logger.info("CME Market Data Gateway stopped");
    }

    private void receiveMarketData() {
        // TODO: Actual UDP receive and decode logic

        long seq = marketDataRing.next();
        try {
            ExternalMarketDataEvent event = marketDataRing.get(seq);
            event.setType(ExternalMarketDataEvent.Type.INCREMENTAL);
            event.setSymbol("ESH5"); // Example symbol
            event.setReceiveTimestamp(System.nanoTime());
            // ... populate other fields
        } finally {
            marketDataRing.publish(seq);
        }
    }

    public void stop() {
        running = false;
    }
}

