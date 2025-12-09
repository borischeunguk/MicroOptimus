package com.microoptimus.signal.cluster;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClusterEgressSubscriber - MM/OSM receives sequenced market data from Aeron Cluster
 *
 * Architecture:
 * 1. Connect to Aeron Cluster as client
 * 2. Receive sequenced MdRefMessage from cluster egress
 * 3. Read actual payload from shared memory (zero-copy)
 * 4. Process market data
 *
 * Global Ordering: All consumers receive events in same sequence (from cluster)
 */
public class ClusterEgressSubscriber implements EgressListener {

    private static final Logger log = LoggerFactory.getLogger(ClusterEgressSubscriber.class);

    private final AeronCluster aeronCluster;
    private final SharedMemoryStore sharedMemory;

    // Metrics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong shmReadFailures = new AtomicLong(0);
    private final Histogram latencyHistogram;
    private boolean measurementPhase = false;

    // Running flag
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Create cluster egress subscriber
     *
     * @param clusterUris Comma-separated list of cluster node URIs
     */
    public ClusterEgressSubscriber(String clusterUris) throws Exception {
        // Connect to existing shared memory
        log.info("Opening shared memory store...");
        String shmPath = "/tmp/md.bin";
        long shmSize = 128 * 1024 * 1024; // 128 MB
        this.sharedMemory = new SharedMemoryStore(shmPath, shmSize);

        // Create Aeron Cluster client
        log.info("Connecting to Aeron Cluster: {}", clusterUris);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints(clusterUris)
                .egressListener(this);  // Subscribe to egress messages

        this.aeronCluster = AeronCluster.connect(clusterContext);

        // Create latency histogram
        this.latencyHistogram = new Histogram(3_600_000_000_000L, 3);

        log.info("ClusterEgressSubscriber initialized");
    }

    @Override
    public void onMessage(
            long clusterSessionId,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {

        long receiveTime = System.nanoTime();

        // Validate message size
        if (length != MdRefMessage.MESSAGE_SIZE) {
            log.error("Invalid message size: {} (expected {})", length, MdRefMessage.MESSAGE_SIZE);
            return;
        }

        // Decode sequenced reference message from cluster (4 bytes)
        MdRefMessage ref = MdRefMessage.decode(buffer, offset);

        // Read payload from shared memory (zero-copy)
        SharedMemoryStore.MarketData md = sharedMemory.readEntry(ref.id());

        // Calculate latency (MDR timestamp → now, includes cluster consensus)
        long latencyNanos = receiveTime - md.timestamp();

        // Process market data
        processMarketData(md, ref.id(), latencyNanos);

        // Update metrics
        messagesReceived.incrementAndGet();

        if (measurementPhase && latencyNanos > 0 && latencyNanos < 3_600_000_000_000L) {
            latencyHistogram.recordValue(latencyNanos);
        }
    }

    @Override
    public void onNewLeader(
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            String ingressEndpoints) {

        log.info("New leader elected: termId={}, memberId={}",
                leadershipTermId, leaderMemberId);
    }

    /**
     * Process market data (placeholder for strategy logic)
     */
    private void processMarketData(
            SharedMemoryStore.MarketData md,
            long entryId,
            long latencyNanos) {

        // TODO: Implement market making strategy
        // For now, just log periodically

        long count = messagesReceived.get();
        if (count % 100 == 0) {
            log.info("Processed MD #{} (id={}): {} | Latency: {} ns",
                    count, entryId, md, latencyNanos);
        }
    }

    /**
     * Start measurement phase
     */
    public void startMeasurement() {
        measurementPhase = true;
        latencyHistogram.reset();
        messagesReceived.set(0);
        shmReadFailures.set(0);
        log.info("Measurement phase started");
    }

    /**
     * Stop measurement phase
     */
    public void stopMeasurement() {
        measurementPhase = false;
        log.info("Measurement phase stopped");
    }

    /**
     * Poll cluster egress (call repeatedly in loop)
     */
    public int poll() {
        return aeronCluster.pollEgress();
    }

    /**
     * Run polling loop (blocking)
     */
    public void run() {
        running.set(true);
        IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        log.info("Starting polling loop...");

        while (running.get()) {
            int fragmentsRead = poll();
            idleStrategy.idle(fragmentsRead);
        }

        log.info("Polling loop stopped");
    }

    /**
     * Stop polling loop
     */
    public void stop() {
        running.set(false);
    }

    /**
     * Get statistics
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getShmReadFailures() {
        return shmReadFailures.get();
    }

    /**
     * Print statistics
     */
    public void printStatistics() {
        long total = messagesReceived.get();
        long failures = shmReadFailures.get();

        log.info("=== Egress Subscriber Statistics ===");
        log.info("Messages received: {}", total);
        log.info("Shared memory read failures: {}", failures);

        if (total > 0) {
            log.info("Success rate: {}%",
                    String.format("%.2f", (total - failures) * 100.0 / total));
        }

        if (latencyHistogram.getTotalCount() > 0) {
            log.info("=== Latency Statistics (MDR → Cluster → MM) ===");
            log.info("  Min:    {} ns", latencyHistogram.getMinValue());
            log.info("  Mean:   {} ns", String.format("%.0f", latencyHistogram.getMean()));
            log.info("  Median: {} ns", latencyHistogram.getValueAtPercentile(50.0));
            log.info("  P90:    {} ns", latencyHistogram.getValueAtPercentile(90.0));
            log.info("  P95:    {} ns", latencyHistogram.getValueAtPercentile(95.0));
            log.info("  P99:    {} ns", latencyHistogram.getValueAtPercentile(99.0));
            log.info("  P99.9:  {} ns", latencyHistogram.getValueAtPercentile(99.9));
            log.info("  P99.99: {} ns", latencyHistogram.getValueAtPercentile(99.99));
            log.info("  Max:    {} ns", latencyHistogram.getMaxValue());
        }
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing ClusterEgressSubscriber...");

        stop();

        if (aeronCluster != null) {
            aeronCluster.close();
        }

        if (sharedMemory != null) {
            sharedMemory.close();
        }

        log.info("ClusterEgressSubscriber closed");
    }
}

