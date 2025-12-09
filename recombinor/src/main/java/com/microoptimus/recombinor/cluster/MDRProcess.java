package com.microoptimus.recombinor.cluster;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * MDRProcess - Market Data Recombinor that publishes to Aeron Cluster
 *
 * Architecture:
 * 1. Generate/receive market data
 * 2. Write full payload to shared memory (id, bestBid, bestAsk, timestamp, venueId)
 * 3. Send tiny reference (4 bytes: id only) to cluster ingress
 * 4. Cluster sequences it globally and sends to all consumers
 *
 * Zero-copy: Payload stays in shared memory, only ID goes through cluster
 */
public class MDRProcess {

    private static final Logger log = LoggerFactory.getLogger(MDRProcess.class);

    private final SharedMemoryStore store;
    private final AeronCluster cluster;
    private final UnsafeBuffer encodeBuffer;
    private int nextId = 0;

    public MDRProcess(String shmPath, long shmSize, String clusterUris) throws Exception {
        // Create shared memory store
        log.info("Creating shared memory store: {}", shmPath);
        this.store = new SharedMemoryStore(shmPath, shmSize);

        // Pre-allocate encode buffer for MD references
        this.encodeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MdRefMessage.MESSAGE_SIZE));

        // Connect to Aeron Cluster (no egress listener needed for MDR)
        log.info("Connecting to Aeron Cluster: {}", clusterUris);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints(clusterUris)
                .egressListener(new NoOpEgressListener());

        this.cluster = AeronCluster.connect(clusterContext);

        log.info("MDRProcess initialized");
    }

    /**
     * Publish market data
     */
    public int publishMarketData(double bestBid, double bestAsk, long timestamp, int venueId) {
        int id = nextId++;

        // 1. Write full payload to shared memory
        store.writeEntry(id, bestBid, bestAsk, timestamp, venueId);

        // 2. Create tiny reference message (4 bytes)
        MdRefMessage ref = new MdRefMessage(id);
        MdRefMessage.encode(ref, encodeBuffer, 0);

        // 3. Send reference to cluster ingress
        long result = cluster.offer(encodeBuffer, 0, MdRefMessage.MESSAGE_SIZE);

        if (result < 0) {
            log.warn("Failed to publish to cluster: result={}", result);
            return -1;
        }

        // Poll egress (keep connection alive)
        cluster.pollEgress();

        return id;
    }

    /**
     * Generate synthetic market data for testing
     */
    public void generateSyntheticData(int count) {
        log.info("Generating {} synthetic market data events", count);

        long startTime = System.nanoTime();
        double bid = 150.00;
        double ask = 150.10;

        for (int i = 0; i < count; i++) {
            // Simulate price movement
            if (i % 10 == 0) {
                bid += (i % 20 == 0) ? -0.05 : 0.05;
                ask = bid + 0.10;
            }

            publishMarketData(bid, ask, System.nanoTime(), 1);

            // Optional: Add small delay
            if (i % 100 == 0) {
                cluster.pollEgress(); // Keep alive
            }
        }

        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughput = (count * 1_000_000_000.0) / (endTime - startTime);

        log.info("Published {} events in {} ms ({} msgs/sec)",
                count, String.format("%.2f", durationMs), String.format("%.0f", throughput));
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing MDRProcess...");

        if (cluster != null) {
            cluster.close();
        }

        if (store != null) {
            store.close();
        }

        log.info("MDRProcess closed");
    }

    /**
     * No-op egress listener (MDR doesn't need egress)
     */
    private static class NoOpEgressListener implements EgressListener {
        @Override
        public void onMessage(
                long clusterSessionId,
                long timestamp,
                DirectBuffer buffer,
                int offset,
                int length,
                Header header) {
            // MDR doesn't process egress
        }

        @Override
        public void onNewLeader(
                long clusterSessionId,
                long leadershipTermId,
                int leaderMemberId,
                String ingressEndpoints) {
            LoggerFactory.getLogger(MDRProcess.class)
                    .info("New leader elected: memberId={}", leaderMemberId);
        }
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        String shmPath = "/dev/shm/md.bin";
        long shmSize = 128 * 1024 * 1024; // 128 MB
        String clusterUris = "localhost:20000,localhost:20100,localhost:20200";

        MDRProcess mdr = new MDRProcess(shmPath, shmSize, clusterUris);

        // Generate test data
        mdr.generateSyntheticData(1000);

        // Keep running for a bit
        Thread.sleep(2000);

        mdr.close();
    }
}

