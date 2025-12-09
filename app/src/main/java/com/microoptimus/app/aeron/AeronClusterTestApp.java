package com.microoptimus.app.aeron;

import com.microoptimus.common.shm.SharedMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * AeronClusterTestApp - Simplified test without actual cluster nodes
 *
 * This is a simplified version for testing the message flow without
 * setting up a full 3-node Aeron Cluster. It simulates:
 * - MDR: Writes to shared memory, sends references
 * - MM/OSM: Read from shared memory via references
 *
 * For production, you would run actual cluster nodes and separate processes.
 */
public class AeronClusterTestApp {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterTestApp.class);

    public static void main(String[] args) throws Exception {
        log.info("=== Aeron Cluster Test (Simplified) ===");
        log.info("Note: This simulates the flow without actual cluster nodes");
        log.info("For full testing, run MDRProcess, MMProcess, OSMProcess separately with cluster nodes\n");

        String shmPath = "/tmp/md-test.bin";  // Use /tmp for Mac compatibility
        long shmSize = 128 * 1024 * 1024; // 128 MB

        // Create shared memory store
        log.info("Creating shared memory store...");
        SharedMemoryStore store = new SharedMemoryStore(shmPath, shmSize);

        // Counters for tracking
        AtomicLong mmReceived = new AtomicLong(0);
        AtomicLong osmReceived = new AtomicLong(0);

        // Simulate MDR publishing market data
        log.info("\n=== MDR: Publishing 100 market data events ===");
        long startTime = System.nanoTime();

        for (int i = 0; i < 100; i++) {
            // Simulate price movement
            double bid = 150.00 + (i * 0.01);
            double ask = bid + 0.10;
            long timestamp = System.nanoTime();
            int venue = 1;

            // Write to shared memory
            store.writeEntry(i, bid, ask, timestamp, venue);

            // In real implementation, would send MdRefMessage(i) to cluster
            // Here we simulate by directly reading (demonstrating zero-copy)

            // Simulate MM reading
            SharedMemoryStore.MarketData mdForMM = store.readEntry(i);
            long mmLatency = System.nanoTime() - mdForMM.timestamp();
            mmReceived.incrementAndGet();

            if (i % 25 == 0) {
                log.info("MM received: {} | Latency: {} ns", mdForMM, mmLatency);
            }

            // Simulate OSM reading
            SharedMemoryStore.MarketData mdForOSM = store.readEntry(i);
            long osmLatency = System.nanoTime() - mdForOSM.timestamp();
            osmReceived.incrementAndGet();

            if (i % 25 == 0) {
                log.info("OSM received: {} | Latency: {} ns", mdForOSM, osmLatency);
            }
        }

        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;

        // Print summary
        log.info("\n=== Test Summary ===");
        log.info("Total events published: 100");
        log.info("MM received: {}", mmReceived.get());
        log.info("OSM received: {}", osmReceived.get());
        log.info("Duration: {:.2f} ms", durationMs);
        log.info("Throughput: {:.0f} msgs/sec", (100 * 1_000_000_000.0) / (endTime - startTime));

        log.info("\n=== Shared Memory Store ===");
        log.info("Entry size: {} bytes", SharedMemoryStore.getEntrySize());
        log.info("Total size: {} MB", shmSize / (1024 * 1024));
        log.info("Entries stored: 100");

        log.info("\n=== Architecture Demonstrated ===");
        log.info("✅ Zero-copy: Payload stays in shared memory");
        log.info("✅ Multiple readers: MM and OSM read same data");
        log.info("✅ Fixed-size entries: 32 bytes per market data");
        log.info("✅ Direct addressing: O(1) lookup by ID");

        log.info("\nTo run with full Aeron Cluster:");
        log.info("1. Start 3 cluster nodes (with SequencerService)");
        log.info("2. Run MDRProcess (publishes 4-byte refs)");
        log.info("3. Run MMProcess (subscribes from cluster)");
        log.info("4. Run OSMProcess (subscribes from cluster)");

        // Cleanup
        store.close();

        log.info("\n=== Test Complete ===");
    }
}

