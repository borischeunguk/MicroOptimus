package com.microoptimus.javamvp.sor.jmh;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.algo.VwapMvpEngine;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.RealAeronIpcSequencer;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;
import com.microoptimus.javamvp.common.Types;
import com.microoptimus.javamvp.sor.RouteDecisionPayload;
import com.microoptimus.javamvp.sor.SorMvpRouter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@State(Scope.Benchmark)
public class E2EAlgoSorLatencyJmh {
    private static final long DEFAULT_SAMPLES = 100L;

    private VwapMvpEngine algo;
    private SorMvpRouter router;
    private MmapSharedRegion region;
    private RealAeronIpcSequencer sequencer;
    private BenchmarkSupport.LatencyRecorder parentRecorder;
    private BenchmarkSupport.LatencyRecorder childRecorder;
    private long samples;
    private long totalChildren;
    private long wallStart;
    private SbeMessages.ParentOrderCommand cmd;
    private VwapMvpEngine.ParentOrder order;
    private SbeMessages.AlgoSliceRefEvent inEvent;
    private SbeMessages.SorRouteRefEvent outEvent;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        algo = new VwapMvpEngine();
        router = new SorMvpRouter();
        region = new MmapSharedRegion(Paths.get(".ipc", "javamvp_mmap_jmh.dat"), 1, 8 * 1024 * 1024);
        sequencer = RealAeronIpcSequencer.launch("sor-e2e-jmh");
        samples = Long.getLong("javamvp.e2e.samples", DEFAULT_SAMPLES);
        parentRecorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples));
        childRecorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples * 8));
        totalChildren = 0;
        wallStart = System.nanoTime();
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
        order = new VwapMvpEngine.ParentOrder();
        order.symbolIndex = 1;
        order.totalQuantity = 40_000;
        order.basePrice = 1500;
        order.startNs = 0;
        order.endNs = 8_000_000;
        order.tickStepNs = 40_000;
        order.numBuckets = 12;
        order.participationRate = 0.12;
        order.maxSliceSize = 4_500;
        inEvent = new SbeMessages.AlgoSliceRefEvent();
        outEvent = new SbeMessages.SorRouteRefEvent();
    }

    @Benchmark
    public long runBenchmark() {
        for (long i = 0; i < samples; i++) {
            long parentStart = System.nanoTime();

            cmd.sequenceId = i + 1;
            cmd.parentOrderId = i + 1;
            cmd.timestamp = i;

            SbeMessages.ParentOrderCommand ordered = SbeMessages.ParentOrderCommand.decode(sequencer.sequenceRoundTrip(cmd.encode()));

            order.parentOrderId = ordered.parentOrderId;
            order.side = ordered.side == 0 ? Types.Side.BUY : Types.Side.SELL;
            order.leavesQuantity = ordered.totalQuantity;

            List<SlicePayload> slices = algo.generateSlices(order);
            for (SlicePayload slice : slices) {
                ShmRef sliceRef = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());

                inEvent.sequenceId = i + 1;
                inEvent.parentOrderId = slice.parentOrderId;
                inEvent.sliceId = slice.sliceId;
                inEvent.timestamp = slice.timestamp;
                inEvent.ref = sliceRef;

                SbeMessages.AlgoSliceRefEvent seqEvent =
                    SbeMessages.AlgoSliceRefEvent.decode(sequencer.sequenceRoundTrip(inEvent.encode()));

                byte[] sliceBytes = region.read(seqEvent.ref);
                SlicePayload decoded = SlicePayload.decode(sliceBytes);

                long childStart = System.nanoTime();
                RouteDecisionPayload decision = router.route(decoded);
                long childElapsed = System.nanoTime() - childStart;
                childRecorder.record(childElapsed);

                ShmRef routeRef = region.write(SbeMessages.TEMPLATE_SOR_ROUTE_REF, decision.encode());
                outEvent.sequenceId = i + 1;
                outEvent.parentOrderId = decision.parentOrderId;
                outEvent.sliceId = decision.sliceId;
                outEvent.routeId = decision.routeId;
                outEvent.timestamp = decision.timestamp;
                outEvent.ref = routeRef;

                SbeMessages.SorRouteRefEvent seqRoute =
                    SbeMessages.SorRouteRefEvent.decode(sequencer.sequenceRoundTrip(outEvent.encode()));
                if (seqRoute.routeId <= 0) {
                    throw new IllegalStateException("invalid route id");
                }
                totalChildren++;
            }

            long parentElapsed = System.nanoTime() - parentStart;
            parentRecorder.record(parentElapsed);
        }

        return totalChildren;
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
        sequencer.close();
    }
}

