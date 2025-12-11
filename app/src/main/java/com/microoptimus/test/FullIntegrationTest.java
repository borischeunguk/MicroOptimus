package com.microoptimus.test;

import com.microoptimus.common.shm.SharedMemoryStore;
import com.microoptimus.recombinor.aeron.AeronRecombinor;
import com.microoptimus.signal.cluster.MMProcess;
import com.microoptimus.osm.cluster.OSMProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FullIntegrationTest - Test the complete architecture with separate processes
 *
 * Tests:
 * 1. Single cluster node
 * 2. MDR (AeronRecombinor) as producer
 * 3. MM and OSM as separate consumers
 * 4. All receiving via cluster egress
 */
public class FullIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FullIntegrationTest.class);

    private static final String SHM_PATH = "/tmp/md_store_full.bin";
    private static final long SHM_SIZE = 1024 * 1024; // 1MB
    private static final String CLUSTER_URIS = "0=localhost:20000";

    public static void main(String[] args) throws Exception {
        log.info("=== Full Integration Test - Separate Processes ===");

        // Cleanup
        cleanup();

        // 1. Start cluster node (using our working MinimalClusterTest setup)
        log.info("Starting cluster node...");
        Thread clusterThread = startClusterNode();
        Thread.sleep(3000);

        try {
            // 2. Start MM Process
            log.info("Starting MM Process...");
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

            // 3. Start OSM Process
            log.info("Starting OSM Process...");
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

            Thread.sleep(2000); // Let consumers connect

            // 4. Start MDR (Producer)
            log.info("Starting AeronRecombinor (MDR)...");
            AeronRecombinor mdr = new AeronRecombinor("INTEGRATION_TEST");

            // Send test data
            log.info("Sending test market data...");
            mdr.generateSyntheticData(10, 50); // 10 messages with 50ms delay

            // Wait for processing
            Thread.sleep(5000);

            // Cleanup
            mdr.close();

        } finally {
            clusterThread.interrupt();
            cleanup();
        }

        log.info("=== Full Integration Test Completed ===");
    }

    private static Thread startClusterNode() {
        Thread thread = new Thread(() -> {
            try {
                // Use the working cluster setup from MinimalClusterTest
                java.io.File clusterDir = new java.io.File(System.getProperty("java.io.tmpdir"), "aeron-cluster-full");
                java.io.File archiveDir = new java.io.File(System.getProperty("java.io.tmpdir"), "aeron-archive-full");
                clusterDir.mkdirs();
                archiveDir.mkdirs();

                // Media Driver configuration (same as MinimalClusterTest)
                io.aeron.driver.MediaDriver.Context mediaDriverContext = new io.aeron.driver.MediaDriver.Context()
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
                io.aeron.cluster.ConsensusModule.Context consensusModuleContext = new io.aeron.cluster.ConsensusModule.Context()
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
                io.aeron.cluster.ClusteredMediaDriver clusteredMediaDriver = io.aeron.cluster.ClusteredMediaDriver.launch(
                        mediaDriverContext,
                        archiveContext,
                        consensusModuleContext);

                // Service configuration
                io.aeron.cluster.service.ClusteredServiceContainer.Context serviceContext =
                        new io.aeron.cluster.service.ClusteredServiceContainer.Context()
                        .clusteredService(new com.microoptimus.common.cluster.SequencerService())
                        .clusterDir(clusterDir)
                        .errorHandler(Throwable::printStackTrace);

                // Start service container
                io.aeron.cluster.service.ClusteredServiceContainer serviceContainer =
                        io.aeron.cluster.service.ClusteredServiceContainer.launch(serviceContext);

                log.info("Cluster started successfully");

                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                }

                // Cleanup
                serviceContainer.close();
                clusteredMediaDriver.close();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Cluster node error", e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void cleanup() {
        try {
            new java.io.File(SHM_PATH).delete();
            deleteDirectory(new java.io.File(System.getProperty("java.io.tmpdir"), "aeron-cluster-full"));
            deleteDirectory(new java.io.File(System.getProperty("java.io.tmpdir"), "aeron-archive-full"));
        } catch (Exception e) {
            // Ignore
        }
    }

    private static void deleteDirectory(java.io.File dir) {
        if (dir.exists()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
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
