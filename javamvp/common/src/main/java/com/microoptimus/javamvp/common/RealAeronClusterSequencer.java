package com.microoptimus.javamvp.common;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single-node real Aeron Cluster harness with a sequencer service.
 */
public final class RealAeronClusterSequencer implements AutoCloseable {
    private final ClusteredMediaDriver clusteredMediaDriver;
    private final ClusteredServiceContainer serviceContainer;
    private final AeronCluster client;
    private final Queue<byte[]> egressQueue = new ArrayDeque<>();
    private final ExpandableArrayBuffer ingressBuffer = new ExpandableArrayBuffer(1024);
    private final IdleStrategy idle = new YieldingIdleStrategy();

    public static RealAeronClusterSequencer launch(String uniqueId) {
        int memberPort = freePort();
        int transferPort = freePort();
        int logPort = freePort();
        int catchupPort = freePort();
        int archiveControlPort = freePort();
        int archiveRecordingEventsPort = freePort();
        int ingressPort = freePort();

        String runId = uniqueId + "-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        String aeronDirName = new File(System.getProperty("java.io.tmpdir"), "javamvp-aeron-" + runId).getAbsolutePath();
        File clusterDir = new File(System.getProperty("java.io.tmpdir"), "javamvp-cluster-" + runId);
        File archiveDir = new File(System.getProperty("java.io.tmpdir"), "javamvp-archive-" + runId);
        clusterDir.mkdirs();
        archiveDir.mkdirs();

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
            .termBufferSparseFile(false)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        io.aeron.archive.Archive.Context archiveContext = new io.aeron.archive.Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .controlChannel("aeron:udp?endpoint=localhost:" + archiveControlPort)
            .recordingEventsChannel("aeron:udp?endpoint=localhost:" + archiveRecordingEventsPort)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .localControlChannel("aeron:ipc")
            .archiveDir(archiveDir)
            .deleteArchiveOnStart(true);

        String members = "0,localhost:" + memberPort
            + ",localhost:" + transferPort
            + ",localhost:" + logPort
            + ",localhost:" + catchupPort
            + ",localhost:" + archiveControlPort;

        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterId(0)
            .clusterMemberId(0)
            .clusterMembers(members)
            .appointedLeaderId(0)
            .aeronDirectoryName(aeronDirName)
            .clusterDir(clusterDir)
            .ingressChannel("aeron:udp?endpoint=localhost:" + ingressPort)
            .logChannel("aeron:udp?control-mode=manual")
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .deleteDirOnStart(true);

        ClusteredMediaDriver cmd = ClusteredMediaDriver.launch(mediaDriverContext, archiveContext, consensusModuleContext);

        ClusteredServiceContainer.Context serviceCtx = new ClusteredServiceContainer.Context()
            .clusteredService(new RealSequencerService())
            .aeronDirectoryName(aeronDirName)
            .clusterDir(clusterDir);
        ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceCtx);

        RealAeronClusterSequencer holder = new RealAeronClusterSequencer(cmd, container, ingressPort, aeronDirName);
        holder.awaitClientConnection();
        return holder;
    }

    /**
     * Like {@link #launch} but uses aeron:ipc for client↔cluster ingress/egress,
     * eliminating UDP socket overhead. Suitable for single-node in-process benchmarks.
     */
    public static RealAeronClusterSequencer launchIpc(String uniqueId) {
        int memberPort = freePort();
        int transferPort = freePort();
        int logPort = freePort();
        int catchupPort = freePort();
        int archiveControlPort = freePort();
        int archiveRecordingEventsPort = freePort();

        String runId = uniqueId + "-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        String aeronDirName = new File(System.getProperty("java.io.tmpdir"), "javamvp-aeron-" + runId).getAbsolutePath();
        File clusterDir = new File(System.getProperty("java.io.tmpdir"), "javamvp-cluster-" + runId);
        File archiveDir = new File(System.getProperty("java.io.tmpdir"), "javamvp-archive-" + runId);
        clusterDir.mkdirs();
        archiveDir.mkdirs();

        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
            .termBufferSparseFile(false)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        io.aeron.archive.Archive.Context archiveContext = new io.aeron.archive.Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .controlChannel("aeron:udp?endpoint=localhost:" + archiveControlPort)
            .recordingEventsChannel("aeron:udp?endpoint=localhost:" + archiveRecordingEventsPort)
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .localControlChannel("aeron:ipc")
            .archiveDir(archiveDir)
            .deleteArchiveOnStart(true);

        String members = "0,localhost:" + memberPort
            + ",localhost:" + transferPort
            + ",localhost:" + logPort
            + ",localhost:" + catchupPort
            + ",localhost:" + archiveControlPort;

        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterId(0)
            .clusterMemberId(0)
            .clusterMembers(members)
            .appointedLeaderId(0)
            .aeronDirectoryName(aeronDirName)
            .clusterDir(clusterDir)
            .ingressChannel("aeron:ipc")
            .logChannel("aeron:udp?control-mode=manual")
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .deleteDirOnStart(true);

        ClusteredMediaDriver cmd = ClusteredMediaDriver.launch(mediaDriverContext, archiveContext, consensusModuleContext);

        ClusteredServiceContainer.Context serviceCtx = new ClusteredServiceContainer.Context()
            .clusteredService(new RealSequencerService())
            .aeronDirectoryName(aeronDirName)
            .clusterDir(clusterDir);
        ClusteredServiceContainer container = ClusteredServiceContainer.launch(serviceCtx);

        RealAeronClusterSequencer holder = new RealAeronClusterSequencer(cmd, container, aeronDirName, runId);
        holder.awaitClientConnection();
        return holder;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to allocate free port", e);
        }
    }

    private RealAeronClusterSequencer(
        ClusteredMediaDriver cmd,
        ClusteredServiceContainer serviceContainer,
        int ingressPort,
        String aeronDirName
    ) {
        this.clusteredMediaDriver = cmd;
        this.serviceContainer = serviceContainer;
        this.client = AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .ingressEndpoints("0=localhost:" + ingressPort)
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) -> {
                    byte[] out = new byte[length];
                    buffer.getBytes(offset, out);
                    egressQueue.add(out);
                })
        );
    }

    private RealAeronClusterSequencer(
        ClusteredMediaDriver cmd,
        ClusteredServiceContainer serviceContainer,
        String aeronDirName,
        String runId
    ) {
        this.clusteredMediaDriver = cmd;
        this.serviceContainer = serviceContainer;
        this.client = AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:ipc")
                .egressChannel("aeron:ipc?alias=cluster-egress-" + runId)
                .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) -> {
                    byte[] out = new byte[length];
                    buffer.getBytes(offset, out);
                    egressQueue.add(out);
                })
        );
    }

    private void awaitClientConnection() {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (client.clusterSessionId() > 0) {
                return;
            }
            client.pollEgress();
            Thread.yield();
        }
        throw new IllegalStateException("Timed out connecting Aeron cluster client");
    }

    public byte[] sequenceRoundTrip(byte[] message) {
        long result;
        do {
            ingressBuffer.putBytes(0, message);
            result = client.offer(ingressBuffer, 0, message.length);
            if (result <= 0) {
                idle.idle();
                client.pollEgress();
            }
        } while (result <= 0);

        while (true) {
            byte[] egress = egressQueue.poll();
            if (egress != null) {
                return egress;
            }
            client.pollEgress();
            idle.idle();
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            serviceContainer.close();
            clusteredMediaDriver.close();
        }
    }
}
