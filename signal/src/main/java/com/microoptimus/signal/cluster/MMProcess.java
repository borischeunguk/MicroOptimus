package com.microoptimus.signal.cluster;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MMProcess - Market Making process that subscribes to Aeron Cluster egress
 *
 * Architecture:
 * 1. Subscribe to cluster egress
 * 2. Receive globally ordered MD_ID events
 * 3. Read full payload directly from shared memory
 * 4. Process market data (strategy logic)
 *
 * Benefits:
 * - Receives globally ordered events (via cluster log)
 * - Zero serialization (reads from shared memory)
 * - Zero copying (direct memory access)
 * - Deterministic ordering guaranteed by cluster
 */
public class MMProcess implements EgressListener {

    private static final Logger log = LoggerFactory.getLogger(MMProcess.class);

    private final SharedMemoryStore store;
    private final AeronCluster cluster;
    private final Histogram latencyHistogram;

    private long messagesReceived = 0;
    private boolean measurementPhase = false;

    public MMProcess(String shmPath, long shmSize, String clusterUris) throws Exception {
        // Open shared memory store (same file as MDR)
        log.info("Opening shared memory store: {}", shmPath);
        this.store = new SharedMemoryStore(shmPath, shmSize);

        // Create latency histogram
        this.latencyHistogram = new Histogram(3_600_000_000_000L, 3);

        // Connect to Aeron Cluster with egress listener
        log.info("Connecting to Aeron Cluster: {}", clusterUris);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints(clusterUris)
                .egressListener(this); // Subscribe to egress

        this.cluster = AeronCluster.connect(clusterContext);

        log.info("MMProcess initialized");
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

        // GLOBAL SEQUENCE: Extract from header.position() - this is our global sequence number
        long globalSequence = header.position();

        // Decode tiny reference (4 bytes)
        MdRefMessage ref = MdRefMessage.decode(buffer, offset);

        // Read full payload from shared memory (zero-copy!)
        SharedMemoryStore.MarketData md = store.readEntry(ref.id());

        // Calculate latency (MDR write → MM read, includes cluster consensus)
        long latency = receiveTime - md.timestamp();

        // Process market data with global sequence
        processMarketData(md, globalSequence, latency);

        messagesReceived++;

        // Record latency during measurement phase
        if (measurementPhase && latency > 0 && latency < 3_600_000_000_000L) {
            latencyHistogram.recordValue(latency);
        }
    }

    @Override
    public void onNewLeader(
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            String ingressEndpoints) {

        log.info("New leader elected: termId={}, memberId={}", leadershipTermId, leaderMemberId);
    }

    /**
     * Process market data (strategy logic placeholder)
     */
    private void processMarketData(SharedMemoryStore.MarketData md, long globalSequence, long latency) {
        // TODO: Implement market making strategy
        // TODO: Use globalSequence for deterministic ordering of strategy decisions

        // Log periodically with global sequence number
        if (messagesReceived % 100 == 0) {
            log.info("MM got MD #{} (seq={}): {} | Latency: {} ns",
                messagesReceived, globalSequence, md, latency);
        }
    }

    /**
     * Start measurement phase
     */
    public void startMeasurement() {
        measurementPhase = true;
        latencyHistogram.reset();
        messagesReceived = 0;
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
     * Run polling loop
     */
    public void run() {
        log.info("Starting polling loop...");

        while (true) {
            cluster.pollEgress();
            Thread.onSpinWait(); // Busy spin
        }
    }

    /**
     * Print statistics
     */
    public void printStatistics() {
        log.info("=== MM Process Statistics ===");
        log.info("Messages received: {}", messagesReceived);

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
        log.info("Closing MMProcess...");

        if (cluster != null) {
            cluster.close();
        }

        if (store != null) {
            store.close();
        }

        log.info("MMProcess closed");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        String shmPath = "/tmp/md_store.bin";
        long shmSize = 128L * 1024 * 1024; // 128 MB
        String clusterUris = "localhost:9000,localhost:9001,localhost:9002";

        MMProcess mm = new MMProcess(shmPath, shmSize, clusterUris);

        mm.startMeasurement();

        // Run for a bit
        Thread runThread = new Thread(mm::run);
        runThread.setDaemon(true);
        runThread.start();

        Thread.sleep(5000);

        mm.stopMeasurement();
        mm.printStatistics();
        mm.close();
    }
}

