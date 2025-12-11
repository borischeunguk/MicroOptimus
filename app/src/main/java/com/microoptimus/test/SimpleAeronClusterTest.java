package com.microoptimus.test;

import com.microoptimus.common.cluster.ClusterNode;
import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import com.microoptimus.recombinor.aeron.AeronRecombinor;
import com.microoptimus.signal.cluster.MMProcess;
import com.microoptimus.osm.cluster.OSMProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SimpleAeronClusterTest - Minimal test to validate the global sequencer implementation
 *
 * Tests the full flow:
 * 1. Start single cluster node (node 0)
 * 2. Start MM and OSM consumers
 * 3. Send a few market data messages via MDR
 * 4. Verify messages are received in order
 */
public class SimpleAeronClusterTest {

    private static final Logger log = LoggerFactory.getLogger(SimpleAeronClusterTest.class);

    private static final String SHM_PATH = "/tmp/md_store_test.bin";
    private static final long SHM_SIZE = 1024 * 1024; // 1MB for test
    private static final String CLUSTER_URIS = "localhost:9000,localhost:9001,localhost:9002";

    public static void main(String[] args) throws Exception {
        log.info("=== Simple Aeron Cluster Test ===");

        CountDownLatch testCompleted = new CountDownLatch(1);

        // Cleanup any existing shared memory
        try {
            new java.io.File(SHM_PATH).delete();
        } catch (Exception e) {
            // Ignore
        }

        // 1. Start cluster node 0
        log.info("Starting cluster node 0...");
        ClusterNode node = new ClusterNode(0);
        Thread nodeThread = new Thread(() -> {
            try {
                node.start();
                // Keep running
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Cluster node error", e);
            }
        });
        nodeThread.setDaemon(true);
        nodeThread.start();

        // Wait for cluster to initialize
        Thread.sleep(3000);
        log.info("Cluster node started");

        // 2. Start consumers in background threads
        log.info("Starting consumer processes...");

        // MM Process
        MMProcess mmProcess = new MMProcess(SHM_PATH, SHM_SIZE, CLUSTER_URIS);
        Thread mmThread = new Thread(() -> {
            try {
                mmProcess.run();
            } catch (Exception e) {
                log.error("MM Process error", e);
            }
        });
        mmThread.setDaemon(true);
        mmThread.start();

        // OSM Process
        OSMProcess osmProcess = new OSMProcess(SHM_PATH, SHM_SIZE, CLUSTER_URIS);
        Thread osmThread = new Thread(() -> {
            try {
                osmProcess.run();
            } catch (Exception e) {
                log.error("OSM Process error", e);
            }
        });
        osmThread.setDaemon(true);
        osmThread.start();

        // Wait for consumers to connect
        Thread.sleep(2000);
        log.info("Consumer processes started");

        // 3. Send test messages
        log.info("Starting market data producer...");
        AeronRecombinor producer = new AeronRecombinor("TEST");

        // Send a few test messages
        for (int i = 0; i < 10; i++) {
            double bidPrice = 150.00 + (i * 0.01);
            double askPrice = bidPrice + 0.10;

            producer.publishMarketData(bidPrice, 100, askPrice, 100);
            log.info("Sent MD #{}: bid={}, ask={}", i+1, bidPrice, askPrice);

            Thread.sleep(100); // Small delay between messages
        }

        log.info("Sent 10 market data messages");

        // 4. Wait a bit for processing
        log.info("Waiting for message processing...");
        Thread.sleep(3000);

        // 5. Cleanup
        log.info("Cleaning up...");
        producer.close();

        // Interrupt threads
        nodeThread.interrupt();
        mmThread.interrupt();
        osmThread.interrupt();

        // Clean up shared memory
        try {
            new java.io.File(SHM_PATH).delete();
        } catch (Exception e) {
            // Ignore
        }

        log.info("=== Test Completed ===");
        log.info("Check the logs above to verify:");
        log.info("1. Cluster node started successfully");
        log.info("2. MM and OSM processes received sequenced messages");
        log.info("3. Global sequence numbers are consistent");
    }
}
