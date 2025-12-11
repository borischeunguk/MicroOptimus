package com.microoptimus.osm.cluster;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSMProcess - Order & Matching Engine that subscribes to Aeron Cluster egress
 *
 * Architecture:
 * 1. Subscribe to cluster egress
 * 2. Receive globally ordered MD_ID events
 * 3. Read full payload directly from shared memory
 * 4. Update internal orderbook state
 *
 * Benefits:
 * - Receives globally ordered events (via cluster log)
 * - Zero serialization (reads from shared memory)
 * - Zero copying (direct memory access)
 * - Deterministic ordering guaranteed by cluster
 */
public class OSMProcess implements EgressListener {

    private static final Logger log = LoggerFactory.getLogger(OSMProcess.class);

    private final SharedMemoryStore store;
    private final AeronCluster cluster;

    private long messagesReceived = 0;

    public OSMProcess(String shmPath, long shmSize, String clusterUris) throws Exception {
        // Open shared memory store (same file as MDR)
        log.info("Opening shared memory store: {}", shmPath);
        this.store = new SharedMemoryStore(shmPath, shmSize);

        // Connect to Aeron Cluster with egress listener
        log.info("Connecting to Aeron Cluster: {}", clusterUris);
        AeronCluster.Context clusterContext = new AeronCluster.Context()
                .ingressChannel("aeron:udp")
                .ingressEndpoints(clusterUris)
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener(this); // Subscribe to egress

        this.cluster = AeronCluster.connect(clusterContext);

        log.info("OSMProcess initialized");
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

        // Calculate latency (MDR write → OSM read, includes cluster consensus)
        long latency = receiveTime - md.timestamp();

        // Process market data with global sequence
        processMarketData(md, globalSequence, latency);

        messagesReceived++;
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
     * Process market data (update orderbook state)
     */
    private void processMarketData(SharedMemoryStore.MarketData md, long globalSequence, long latency) {
        // TODO: Update internal orderbook with market data
        // TODO: Use globalSequence for deterministic ordering of operations

        // Log periodically with global sequence number
        if (messagesReceived % 100 == 0) {
            log.info("OSM got MD #{} (seq={}): {} | Latency: {} ns",
                messagesReceived, globalSequence, md, latency);
        }
    }

    /**
     * Run polling loop
     */
    public void run() {
        log.info("Starting OSM polling loop...");

        while (true) {
            cluster.pollEgress();
            Thread.onSpinWait(); // Busy spin
        }
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing OSMProcess...");

        if (cluster != null) {
            cluster.close();
        }

        if (store != null) {
            store.close();
        }

        log.info("OSMProcess closed");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        String shmPath = "/tmp/md_store.bin";
        long shmSize = 128L * 1024 * 1024; // 128 MB
        String clusterUris = "localhost:9000,localhost:9001,localhost:9002";

        OSMProcess osm = new OSMProcess(shmPath, shmSize, clusterUris);

        // Run
        osm.run();
    }
}

