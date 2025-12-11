package com.microoptimus.test;

import com.microoptimus.common.cluster.SequencerService;
import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * MinimalClusterTest - Simplified single-node cluster test
 *
 * Creates a minimal cluster setup for testing global sequencing
 */
public class MinimalClusterTest {

    private static final Logger log = LoggerFactory.getLogger(MinimalClusterTest.class);

    private static final String SHM_PATH = "/tmp/md_store_minimal.bin";
    private static final long SHM_SIZE = 1024 * 1024; // 1MB

    public static void main(String[] args) throws Exception {
        log.info("=== Minimal Aeron Cluster Test ===");

        // Cleanup any existing shared memory and cluster directories
        cleanup();

        // Create shared memory store
        log.info("Creating shared memory store...");
        SharedMemoryStore store = new SharedMemoryStore(SHM_PATH, SHM_SIZE);

        // Setup cluster directories
        File clusterDir = new File(System.getProperty("java.io.tmpdir"), "aeron-cluster-minimal");
        File archiveDir = new File(System.getProperty("java.io.tmpdir"), "aeron-archive-minimal");
        clusterDir.mkdirs();
        archiveDir.mkdirs();

        // Media Driver configuration
        log.info("Starting media driver...");
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
                .termBufferSparseFile(false)
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true);

        // Archive configuration
        io.aeron.archive.Archive.Context archiveContext = new io.aeron.archive.Archive.Context()
                .controlChannel("aeron:udp?endpoint=localhost:8010")
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .localControlChannel("aeron:ipc")
                .archiveDir(archiveDir)
                .errorHandler(Throwable::printStackTrace);

        // Consensus Module configuration (single node cluster)
        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                .clusterId(0)
                .clusterMemberId(0)
                .clusterMembers("0,localhost:20110,localhost:20111,localhost:20112,localhost:20113,localhost:8010")
                .appointedLeaderId(0)
                .clusterDir(clusterDir)
                .ingressChannel("aeron:udp?endpoint=localhost:20000")
                .logChannel("aeron:udp?control-mode=manual")
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .errorHandler(Throwable::printStackTrace);

        // Start clustered media driver
        log.info("Starting cluster...");
        ClusteredMediaDriver clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext,
                archiveContext,
                consensusModuleContext);

        // Service configuration
        ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                .clusteredService(new SequencerService())
                .clusterDir(clusterDir)
                .errorHandler(Throwable::printStackTrace);

        // Start service container
        ClusteredServiceContainer serviceContainer = ClusteredServiceContainer.launch(serviceContext);

        log.info("Cluster started, waiting for initialization...");
        Thread.sleep(3000);

        try {
            // Connect as client and send some messages
            log.info("Connecting as cluster client...");
            AeronCluster.Context clientContext = new AeronCluster.Context()
                    .ingressChannel("aeron:udp")
                    .ingressEndpoints("0=localhost:20000")
                    .egressChannel("aeron:udp?endpoint=localhost:0")
                    .egressListener((sessionId, timestamp, buffer, offset, length, header) -> {
                        long globalSequence = header.position();
                        MdRefMessage ref = MdRefMessage.decode(buffer, offset);

                        // Read from shared memory
                        SharedMemoryStore.MarketData md = store.readEntry(ref.id());

                        log.info("Received sequenced message: seq={}, id={}, md={}",
                                globalSequence, ref.id(), md);
                    });

            AeronCluster client = AeronCluster.connect(clientContext);

            log.info("Client connected, sending test messages...");

            // Send test messages
            UnsafeBuffer encodeBuffer = new UnsafeBuffer(ByteBuffer.allocate(64));

            for (int i = 0; i < 5; i++) {
                // Write to shared memory first
                store.writeEntry(i, 150.0 + i, 150.1 + i, System.nanoTime(), 1);

                // Send reference message
                MdRefMessage ref = new MdRefMessage(i);
                MdRefMessage.encode(ref, encodeBuffer, 0);

                long result = client.offer(encodeBuffer, 0, MdRefMessage.MESSAGE_SIZE);
                if (result > 0) {
                    log.info("Sent message #{}: id={}", i + 1, i);
                } else {
                    log.warn("Failed to send message #{}: result={}", i + 1, result);
                }

                Thread.sleep(100);
            }

            // Poll for responses
            log.info("Polling for responses...");
            for (int i = 0; i < 50; i++) {
                client.pollEgress();
                Thread.sleep(100);
            }

            // Cleanup
            client.close();

        } catch (Exception e) {
            log.error("Client error", e);
        } finally {
            log.info("Shutting down cluster...");

            if (serviceContainer != null) {
                serviceContainer.close();
            }
            if (clusteredMediaDriver != null) {
                clusteredMediaDriver.close();
            }
            if (store != null) {
                store.close();
            }

            cleanup();
            log.info("=== Test Completed ===");
        }
    }

    private static void cleanup() {
        try {
            new File(SHM_PATH).delete();
            deleteDirectory(new File(System.getProperty("java.io.tmpdir"), "aeron-cluster-minimal"));
            deleteDirectory(new File(System.getProperty("java.io.tmpdir"), "aeron-archive-minimal"));
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
