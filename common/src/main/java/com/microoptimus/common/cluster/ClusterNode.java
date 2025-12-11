package com.microoptimus.common.cluster;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClusterNode - Aeron Cluster Node Runner
 *
 * Runs a single node of the Aeron Cluster that hosts the SequencerService
 *
 * Usage:
 * - Node 0: java ClusterNode 0
 * - Node 1: java ClusterNode 1
 * - Node 2: java ClusterNode 2
 */
public class ClusterNode {

    private static final Logger log = LoggerFactory.getLogger(ClusterNode.class);

    // Cluster configuration
    private static final int BASE_PORT = 9000;
    private static final int CLUSTER_MEMBERS = 3;

    private final int nodeId;
    private final String clusterMembers;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer serviceContainer;

    public ClusterNode(int nodeId) {
        this.nodeId = nodeId;
        this.clusterMembers = buildClusterMembers();
    }

    private String buildClusterMembers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CLUSTER_MEMBERS; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(i).append(",localhost:").append(BASE_PORT + i * 100).append(",localhost:").append(BASE_PORT + i * 100 + 1)
                    .append(",localhost:").append(BASE_PORT + i * 100 + 2).append(",localhost:").append(BASE_PORT + i * 100 + 3);
        }
        return sb.toString();
    }

    public void start() throws Exception {
        log.info("Starting cluster node {} of {}", nodeId, CLUSTER_MEMBERS);
        log.info("Cluster members: {}", clusterMembers);

        // Media Driver configuration
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true);

        // Consensus Module configuration
        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                .clusterId(0)
                .clusterMemberId(nodeId)
                .clusterMembers(clusterMembers)
                .appointedLeaderId(0)
                .errorHandler(Throwable::printStackTrace);

        // Create clustered media driver
        clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext,
                new io.aeron.archive.Archive.Context().controlChannel("aeron:udp?endpoint=localhost:" + (BASE_PORT + nodeId * 100 + 10)),
                consensusModuleContext);

        // Service configuration
        ClusteredServiceContainer.Context serviceContext = new ClusteredServiceContainer.Context()
                .clusteredService(new SequencerService())
                .errorHandler(Throwable::printStackTrace);

        // Create service container
        serviceContainer = ClusteredServiceContainer.launch(serviceContext);

        log.info("Cluster node {} started successfully", nodeId);
    }

    public void stop() {
        log.info("Stopping cluster node {}", nodeId);

        if (serviceContainer != null) {
            serviceContainer.close();
        }

        if (clusteredMediaDriver != null) {
            clusteredMediaDriver.close();
        }

        log.info("Cluster node {} stopped", nodeId);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java ClusterNode <nodeId>");
            System.err.println("  nodeId: 0, 1, or 2");
            System.exit(1);
        }

        int nodeId = Integer.parseInt(args[0]);
        if (nodeId < 0 || nodeId >= CLUSTER_MEMBERS) {
            System.err.println("nodeId must be 0, 1, or 2");
            System.exit(1);
        }

        ClusterNode node = new ClusterNode(nodeId);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(node::stop));

        try {
            node.start();

            log.info("Cluster node {} is running. Press Ctrl+C to stop.", nodeId);

            // Keep running
            while (true) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            log.error("Error running cluster node {}", nodeId, e);
            node.stop();
            System.exit(1);
        }
    }
}
