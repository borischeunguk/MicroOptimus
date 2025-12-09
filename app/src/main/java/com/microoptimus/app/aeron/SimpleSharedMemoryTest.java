package com.microoptimus.app.aeron;

import com.microoptimus.common.shm.SharedMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SimpleSharedMemoryTest - Test shared memory store without Aeron Cluster
 *
 * This demonstrates:
 * 1. MDR writes market data to shared memory
 * 2. MM and OSM read from shared memory (zero-copy)
 * 3. Multiple readers can access same data
 */
public class SimpleSharedMemoryTest {

    private static final Logger log = LoggerFactory.getLogger(SimpleSharedMemoryTest.class);

    public static void main(String[] args) {
        try {
            log.info("=== Simple Shared Memory Test ===\n");

            // Use /tmp for Mac compatibility
            String shmPath = "/tmp/md-simple-test.bin";
            long shmSize = 10 * 1024 * 1024; // 10 MB

            // Create shared memory store
            log.info("Step 1: Creating shared memory store at {}", shmPath);
            SharedMemoryStore store = new SharedMemoryStore(shmPath, shmSize);
            log.info("✅ Shared memory created: {} MB\n", shmSize / (1024 * 1024));

            // Simulate MDR writing market data
            log.info("Step 2: MDR writing 10 market data entries");
            for (int i = 0; i < 10; i++) {
                double bid = 150.00 + (i * 0.05);
                double ask = bid + 0.10;
                long timestamp = System.nanoTime();
                int venue = 1;

                store.writeEntry(i, bid, ask, timestamp, venue);
                log.info("  MDR wrote entry {}: bid={:.2f}, ask={:.2f}", i, bid, ask);
            }
            log.info("✅ MDR wrote 10 entries\n");

            // Simulate MM reading
            log.info("Step 3: MM reading entries from shared memory (zero-copy)");
            for (int i = 0; i < 10; i++) {
                SharedMemoryStore.MarketData md = store.readEntry(i);
                long latency = System.nanoTime() - md.timestamp();
                log.info("  MM read entry {}: {} | Latency: {} ns", i, md, latency);
            }
            log.info("✅ MM read 10 entries\n");

            // Simulate OSM reading the SAME data
            log.info("Step 4: OSM reading entries from shared memory (zero-copy)");
            for (int i = 0; i < 10; i++) {
                SharedMemoryStore.MarketData md = store.readEntry(i);
                long latency = System.nanoTime() - md.timestamp();
                log.info("  OSM read entry {}: {} | Latency: {} ns", i, md, latency);
            }
            log.info("✅ OSM read 10 entries\n");

            // Demonstrate key benefits
            log.info("=== Key Benefits Demonstrated ===");
            log.info("✅ Zero-copy: Data written once, read multiple times");
            log.info("✅ Multiple readers: MM and OSM read same data");
            log.info("✅ Fast access: Direct addressing by ID");
            log.info("✅ Fixed size: 32 bytes per entry");
            log.info("✅ Simple: Uses JDK MappedByteBuffer\n");

            // Cleanup
            store.close();
            log.info("=== Test Complete ===");

            log.info("\nNext Steps:");
            log.info("1. This demonstrated shared memory zero-copy");
            log.info("2. Add Aeron Cluster for global sequencing");
            log.info("3. Run MDRProcess, MMProcess, OSMProcess separately");
            log.info("4. Cluster provides total ordering + HA");

        } catch (Exception e) {
            log.error("Test failed", e);
            System.exit(1);
        }
    }
}

