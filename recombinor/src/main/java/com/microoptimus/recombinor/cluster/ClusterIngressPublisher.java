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
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClusterIngressPublisher - MDR publishes market data to Aeron Cluster
 *
 * Architecture:
 * 1. Write market data payload to shared memory
 * 2. Send tiny reference (4 bytes) to cluster ingress
 * 3. Cluster sequences and broadcasts to all consumers
 *
 * Zero-copy: Only references go through cluster, payloads in shared memory
 */
public class ClusterIngressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClusterIngressPublisher.class);

    private final AeronCluster aeronCluster;
    private final SharedMemoryStore sharedMemory;
    private final UnsafeBuffer encodeBuffer;
    private final String symbol;

    private long localSequence = 0;

    // Metrics
    private final AtomicLong messagesPublished = new AtomicLong(0);
    private final AtomicLong publishFailures = new AtomicLong(0);

    /**
     * Create cluster ingress publisher
     *
     * @param clusterUris Comma-separated list of cluster node URIs
     * @param symbol Trading symbol
     */
    public ClusterIngressPublisher(String clusterUris, String symbol) throws Exception {
        this.symbol = symbol;

        // Create shared memory
        log.info("Creating shared memory store...");
        String shmPath = "/tmp/md.bin";
        long shmSize = 128 * 1024 * 1024; // 128 MB
        this.sharedMemory = new SharedMemoryStore(shmPath, shmSize);

        // Pre-allocate encode buffer
        this.encodeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MdRefMessage.MESSAGE_SIZE));

        // Create Aeron Cluster client
        log.info("Connecting to Aeron Cluster: {}", clusterUris);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")  // Use UDP for cluster communication
                .ingressEndpoints(clusterUris)
                .egressListener(new NoOpEgressListener());  // MDR doesn't need egress

        this.aeronCluster = AeronCluster.connect(clusterContext);

        log.info("ClusterIngressPublisher initialized for symbol: {}", symbol);
    }

    /**
     * Publish market data update
     */
    public long publishMarketData(long bidPrice, long bidSize, long askPrice, long askSize) {
        long timestamp = System.nanoTime();
        long seq = localSequence++;

        try {
            // 1. Write payload to shared memory (32 bytes)
            sharedMemory.writeEntry((int)seq, bidPrice, askPrice, timestamp, 1); // venue=1

            // 2. Encode reference message (4 bytes)
            MdRefMessage ref = new MdRefMessage((int)seq);
            MdRefMessage.encode(ref, encodeBuffer, 0);

            // 3. Send reference to cluster ingress
            long result = aeronCluster.offer(encodeBuffer, 0, MdRefMessage.MESSAGE_SIZE);

            if (result > 0) {
                messagesPublished.incrementAndGet();
            } else {
                publishFailures.incrementAndGet();
                log.warn("Failed to publish to cluster: result={}", result);
            }

            return seq;

        } catch (Exception e) {
            publishFailures.incrementAndGet();
            log.error("Error publishing market data", e);
            return -1;
        }
    }

    /**
     * Generate synthetic market data for testing
     */
    public void generateSyntheticData(int count, long delayNanos) {
        log.info("Generating {} synthetic market data events", count);

        long startTime = System.nanoTime();

        // Starting prices
        long bidPrice = 150_00; // $150.00
        long askPrice = 150_10; // $150.10

        for (int i = 0; i < count; i++) {
            // Simulate price movement
            if (i % 10 == 0) {
                bidPrice += (i % 20 == 0) ? -5 : 5;
                askPrice = bidPrice + 10;
            }

            publishMarketData(bidPrice, 100, askPrice, 100);

            // Poll cluster (send heartbeats, process responses)
            aeronCluster.pollEgress();

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
    public long getMessagesPublished() {
        return messagesPublished.get();
    }

    public long getPublishFailures() {
        return publishFailures.get();
    }

    public SharedMemoryStore getSharedMemory() {
        return sharedMemory;
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing ClusterIngressPublisher...");

        if (aeronCluster != null) {
            aeronCluster.close();
        }

        if (sharedMemory != null) {
            sharedMemory.close();
        }

        log.info("ClusterIngressPublisher closed");
    }

    /**
     * No-op egress listener (MDR doesn't need egress messages)
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
            // MDR doesn't process egress - ignore
        }

        @Override
        public void onNewLeader(
                long clusterSessionId,
                long leadershipTermId,
                int leaderMemberId,
                String ingressEndpoints) {
            // Log for debugging
            LoggerFactory.getLogger(ClusterIngressPublisher.class)
                    .info("New leader elected: memberId={}", leaderMemberId);
        }
    }
}

