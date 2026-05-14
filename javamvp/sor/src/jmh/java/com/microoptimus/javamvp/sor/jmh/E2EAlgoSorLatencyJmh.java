package com.microoptimus.javamvp.sor.jmh;

import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.CrossProcessAeronIpcTransport;
import com.microoptimus.javamvp.common.E2EIpcConfig;
import com.microoptimus.javamvp.common.EmbeddedAeronMediaDriver;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.sor.RouteDecisionPayload;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

@State(Scope.Benchmark)
public class E2EAlgoSorLatencyJmh {
    private static final long DEFAULT_SAMPLES = 100L;
    private static final long SHUTDOWN_SEQUENCE_ID = -1L;
    private static final long DEFAULT_TIMEOUT_NS = 5_000_000L;
    private static final long DEFAULT_STARTUP_TIMEOUT_NS = 30_000_000_000L;

    private MmapSharedRegion region;
    private EmbeddedAeronMediaDriver mediaDriver;
    private CrossProcessAeronIpcTransport transport;
    private CrossProcessAeronIpcTransport.BlockingPublication coordToAlgoPub;
    private CrossProcessAeronIpcTransport.BlockingSubscription sorToCoordSub;
    private CrossProcessAeronIpcTransport.BlockingPublication controlPub;
    private CrossProcessAeronIpcTransport.BlockingSubscription controlSub;
    private BenchmarkSupport.LatencyRecorder parentRecorder;
    private BenchmarkSupport.LatencyRecorder childRecorder;
    private long samples;
    private long timeoutNs;
    private long startupTimeoutNs;
    private long totalChildren;
    private int expectedChildrenPerParent;
    private long wallStart;
    private String aeronDir;
    private String mmapPath;
    private Process algoProcess;
    private Process sorProcess;
    private SbeMessages.ParentOrderCommand cmd;
    private SbeMessages.ControlMessage controlMsg;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        samples = Long.getLong("javamvp.e2e.samples", DEFAULT_SAMPLES);
        timeoutNs = Long.getLong("javamvp.e2e.timeout.ns", DEFAULT_TIMEOUT_NS);
        startupTimeoutNs = Long.getLong("javamvp.e2e.startup.timeout.ns", DEFAULT_STARTUP_TIMEOUT_NS);

        cmd = new SbeMessages.ParentOrderCommand();
        cmd.clientId = 1;
        cmd.symbolIndex = 1;
        cmd.side = 0;
        cmd.totalQuantity = 40_000;
        cmd.limitPrice = 1500;
        cmd.startTime = 0;
        cmd.endTime = 8_000_000;
        cmd.numBuckets = 12;
        cmd.participationRate = 0.12;
        cmd.minSliceSize = 100;
        cmd.maxSliceSize = 4_500;
        controlMsg = new SbeMessages.ControlMessage();

        expectedChildrenPerParent = expectedChildren(
            cmd.totalQuantity,
            cmd.participationRate,
            cmd.numBuckets,
            cmd.maxSliceSize,
            cmd.startTime,
            cmd.endTime,
            40_000L);

        parentRecorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples));
        childRecorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples * expectedChildrenPerParent));
        totalChildren = 0;
        wallStart = System.nanoTime();

        String runId = "e2e-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        aeronDir = new File(System.getProperty("java.io.tmpdir"), "javamvp-e2e-" + runId).getAbsolutePath();
        mmapPath = Paths.get(".ipc", "javamvp_mmap_jmh_" + runId + ".dat").toString();
        region = new MmapSharedRegion(Paths.get(mmapPath), 1, 8 * 1024 * 1024);

        mediaDriver = EmbeddedAeronMediaDriver.launch(aeronDir);
        sorProcess = launchService("com.microoptimus.javamvp.sor.SorE2eServiceMain");
        algoProcess = launchService("com.microoptimus.javamvp.algo.AlgoE2eServiceMain");

        transport = CrossProcessAeronIpcTransport.connect(aeronDir);
        coordToAlgoPub = transport.addPublication(E2EIpcConfig.STREAM_COORD_TO_ALGO);
        sorToCoordSub = transport.addSubscription(E2EIpcConfig.STREAM_SOR_TO_COORD);
        controlPub = transport.addPublication(E2EIpcConfig.STREAM_COORD_TO_SVC_CONTROL);
        controlSub = transport.addSubscription(E2EIpcConfig.STREAM_SVC_TO_COORD_CONTROL);

        assertAlive(algoProcess, "algo");
        assertAlive(sorProcess, "sor");
        awaitBothReady();
        sendControl(E2EIpcConfig.SERVICE_ALGO, E2EIpcConfig.CONTROL_START);
        sendControl(E2EIpcConfig.SERVICE_SOR, E2EIpcConfig.CONTROL_START);
    }

    @Benchmark
    public long runBenchmark() {
        try {
        for (long i = 0; i < samples; i++) {
            long parentStart = System.nanoTime();

            cmd.sequenceId = i + 1;
            cmd.parentOrderId = i + 1;
            cmd.timestamp = i;

            coordToAlgoPub.offerBlocking(cmd.encode(), timeoutNs);

            int receivedForParent = 0;
            long childStart = 0;
            while (receivedForParent < expectedChildrenPerParent) {
                childStart = System.nanoTime();
                CrossProcessAeronIpcTransport.PollResult out = sorToCoordSub.pollBlocking(timeoutNs);
                SbeMessages.SorRouteRefEvent seqRoute = SbeMessages.SorRouteRefEvent.decode(out.payload);
                if (seqRoute.parentOrderId != cmd.parentOrderId) {
                    throw new IllegalStateException("out-of-order parent ack, expected=" + cmd.parentOrderId
                        + " got=" + seqRoute.parentOrderId);
                }
                if (seqRoute.routeId <= 0) {
                    throw new IllegalStateException("invalid route id");
                }
                RouteDecisionPayload decision = RouteDecisionPayload.decode(region.read(seqRoute.ref));
                childRecorder.record(System.nanoTime() - childStart);
                receivedForParent++;
                totalChildren++;
            }

            long parentElapsed = System.nanoTime() - parentStart;
            parentRecorder.record(parentElapsed);
        }

        return totalChildren;
        } catch (CrossProcessAeronIpcTransport.TimeoutException e) {
            throw new IllegalStateException("E2E benchmark failed on bounded timeout", e);
        }
    }

    private Process launchService(String mainClass) throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
            javaBin,
            "-Djavamvp.e2e.aeron.dir=" + aeronDir,
            "-Djavamvp.e2e.mmap.path=" + mmapPath,
            "-Djavamvp.e2e.timeout.ns=" + timeoutNs,
            "-Djavamvp.e2e.startup.timeout.ns=" + startupTimeoutNs,
            "-cp",
            classpath,
            mainClass);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return pb.start();
    }

    private static void assertAlive(Process process, String name) {
        if (!process.isAlive()) {
            throw new IllegalStateException(name + " service exited unexpectedly with code " + process.exitValue());
        }
    }

    private static int expectedChildren(
        long totalQuantity,
        double participationRate,
        int numBuckets,
        long maxSliceSize,
        long startNs,
        long endNs,
        long tickStepNs
    ) {
        long leaves = totalQuantity;
        int count = 0;
        long now = startNs;
        while (now <= endNs && leaves > 0) {
            long target = Math.max(100L, (long) (totalQuantity * participationRate / numBuckets));
            long qty = Math.min(leaves, Math.min(target, maxSliceSize));
            if (qty > 0) {
                leaves -= qty;
                count++;
            }
            now += tickStepNs;
        }
        if (leaves > 0) {
            count++;
        }
        return count;
    }

    private void awaitBothReady() throws CrossProcessAeronIpcTransport.TimeoutException {
        long deadline = System.nanoTime() + startupTimeoutNs;
        boolean algoReady = false;
        boolean sorReady = false;
        while (System.nanoTime() < deadline) {
            try {
                SbeMessages.ControlMessage msg = SbeMessages.ControlMessage.decode(controlSub.pollBlocking(timeoutNs).payload);
                if (msg.command == E2EIpcConfig.CONTROL_READY) {
                    if (msg.serviceId == E2EIpcConfig.SERVICE_ALGO) {
                        algoReady = true;
                    } else if (msg.serviceId == E2EIpcConfig.SERVICE_SOR) {
                        sorReady = true;
                    }
                    if (algoReady && sorReady) {
                        return;
                    }
                }
            } catch (CrossProcessAeronIpcTransport.TimeoutException ignored) {
                // Keep waiting until startup timeout budget is exhausted.
            }
        }
        throw new CrossProcessAeronIpcTransport.TimeoutException("Timed out waiting READY from services");
    }

    private void sendControl(int serviceId, int command) throws CrossProcessAeronIpcTransport.TimeoutException {
        controlMsg.sequenceId++;
        controlMsg.serviceId = serviceId;
        controlMsg.command = command;
        controlPub.offerBlocking(controlMsg.encode(), startupTimeoutNs);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        long wallElapsed = System.nanoTime() - wallStart;
        double sec = wallElapsed / 1_000_000_000.0;

        String json = "{\n"
            + "  \"bench\": \"e2e_algo_sor_latency\",\n"
            + "  \"scenario\": \"e2e_s1_steady\",\n"
            + "  \"samples\": " + samples + ",\n"
            + "  \"parent_latency_ns_p90\": " + parentRecorder.quantile(0.90) + ",\n"
            + "  \"parent_latency_ns_p99\": " + parentRecorder.quantile(0.99) + ",\n"
            + "  \"parent_latency_ns_p999\": " + parentRecorder.quantile(0.999) + ",\n"
            + "  \"child_latency_ns_p90\": " + childRecorder.quantile(0.90) + ",\n"
            + "  \"child_latency_ns_p99\": " + childRecorder.quantile(0.99) + ",\n"
            + "  \"child_latency_ns_p999\": " + childRecorder.quantile(0.999) + ",\n"
            + "  \"throughput_parent_per_sec\": " + (samples / sec) + ",\n"
            + "  \"throughput_child_per_sec\": " + (totalChildren / sec) + ",\n"
            + "  \"total_children\": " + totalChildren + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_e2e_algo_sor_latency_e2e_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);

        try {
            sendControl(E2EIpcConfig.SERVICE_ALGO, E2EIpcConfig.CONTROL_STOP);
            sendControl(E2EIpcConfig.SERVICE_SOR, E2EIpcConfig.CONTROL_STOP);

            SbeMessages.ParentOrderCommand shutdown = new SbeMessages.ParentOrderCommand();
            shutdown.sequenceId = SHUTDOWN_SEQUENCE_ID;
            coordToAlgoPub.offerBlocking(shutdown.encode(), startupTimeoutNs);
        } catch (Exception ignored) {
            // Best-effort shutdown signal before forceful process termination.
        }

        if (coordToAlgoPub != null) {
            coordToAlgoPub.close();
        }
        if (sorToCoordSub != null) {
            sorToCoordSub.close();
        }
        if (controlPub != null) {
            controlPub.close();
        }
        if (controlSub != null) {
            controlSub.close();
        }
        if (transport != null) {
            transport.close();
        }

        stopProcess(algoProcess);
        stopProcess(sorProcess);

        if (mediaDriver != null) {
            mediaDriver.close();
        }
    }

    private static void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}

