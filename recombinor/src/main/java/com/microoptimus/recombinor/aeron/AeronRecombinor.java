package com.microoptimus.recombinor.aeron;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AeronRecombinor - Market Data Recombinor using Aeron + Shared Memory
 *
 * Architecture:
 * 1. Receive raw market data (simulated)
 * 2. Write payload to shared memory
 * 3. Send tiny reference message via Aeron IPC
 *
 * Zero-copy: Consumers read payload directly from shared memory
 */
public class AeronRecombinor {

    private static final Logger log = LoggerFactory.getLogger(AeronRecombinor.class);

    // Cluster configuration
    private static final String CLUSTER_URIS = "localhost:9000,localhost:9001,localhost:9002";

    private final AeronCluster cluster;
    private final SharedMemoryStore sharedMemory;
    private final UnsafeBuffer encodeBuffer;

    // Thread-safe ID generator for shared memory entries
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final String symbol;

    public AeronRecombinor(String symbol) throws Exception {
        this.symbol = symbol;

        // Connect to Aeron Cluster as ingress client
        log.info("Connecting to Aeron Cluster: {}", CLUSTER_URIS);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints(CLUSTER_URIS);

        this.cluster = AeronCluster.connect(clusterContext);

        log.info("Connected to Aeron Cluster");

        // Create shared memory
        log.info("Creating shared memory store...");
        String shmPath = "/tmp/md_store.bin";
        long shmSize = 128L * 1024 * 1024; // 128 MB
        this.sharedMemory = new SharedMemoryStore(shmPath, shmSize);

        // Pre-allocate encode buffer
        this.encodeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MdRefMessage.MESSAGE_SIZE));

        log.info("AeronRecombinor initialized for symbol: {}", symbol);
    }

    /**
     * Publish market data update
     *
     * @param bidPrice Best bid price
     * @param bidSize Best bid size
     * @param askPrice Best ask price
     * @param askSize Best ask size
     * @return Sequence number
     */
    public long publishMarketData(double bidPrice, long bidSize, double askPrice, long askSize) {
        long timestamp = System.nanoTime();
        int id = nextId.getAndIncrement();

        // 1. Write payload to shared memory (32 bytes)
        sharedMemory.writeEntry(id, bidPrice, askPrice, timestamp, 1); // venue=1

        // 2. Encode reference message (4 bytes)
        MdRefMessage ref = new MdRefMessage(id);
        MdRefMessage.encode(ref, encodeBuffer, 0);

        // 3. Publish reference via Aeron Cluster (sends to sequencer)
        long result = cluster.offer(encodeBuffer, 0, MdRefMessage.MESSAGE_SIZE);

        if (result < 0) {
            log.warn("Cluster offer failed: {}", result);
        }

        return id;
    }

    /**
     * Generate synthetic market data for testing
     */
    public void generateSyntheticData(int count, long delayNanos) {
        log.info("Generating {} synthetic market data events", count);

        long startTime = System.nanoTime();

        // Starting prices
        double bidPrice = 150.00; // $150.00
        double askPrice = 150.10; // $150.10

        for (int i = 0; i < count; i++) {
            // Simulate price movement
            if (i % 10 == 0) {
                bidPrice += (i % 20 == 0) ? -0.05 : 0.05;
                askPrice = bidPrice + 0.10;
            }

            publishMarketData(bidPrice, 100, askPrice, 100);

            // Optional delay
            if (delayNanos > 0) {
                long target = System.nanoTime() + delayNanos;
                while (System.nanoTime() < target) {
                    // Busy spin
                }
            }
        }

        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMs = durationNanos / 1_000_000.0;
        double throughput = (count * 1_000_000_000.0) / durationNanos;

        log.info("Published {} events in {} ms ({} msgs/sec)",
                count, String.format("%.2f", durationMs), String.format("%.0f", throughput));
    }

    /**
     * Get statistics
     */
    public long getEventsPublished() {
        return nextId.get();
    }

    public SharedMemoryStore getSharedMemory() {
        return sharedMemory;
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing AeronRecombinor...");

        if (cluster != null) {
            cluster.close();
        }

        if (sharedMemory != null) {
            sharedMemory.close();
        }

        log.info("AeronRecombinor closed");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        AeronRecombinor recombinor = new AeronRecombinor("AAPL");

        // Generate test data
        recombinor.generateSyntheticData(1000, 0);

        // Keep running for a bit
        Thread.sleep(5000);

        recombinor.close();
    }
}

