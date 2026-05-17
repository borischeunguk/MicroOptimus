package com.microoptimus.javamvp.algo.jmh;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.algo.VwapMvpEngine;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.RealAeronIpcSequencer;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.Types;
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
public class VwapLatencyJmh {
    private static final long DEFAULT_SAMPLES = 1_000_000L;

    private VwapMvpEngine engine;
    private RealAeronIpcSequencer sequencer;
    private BenchmarkSupport.LatencyRecorder recorder;
    private long samples;
    private long totalChildren;
    private long wallStart;
    private SbeMessages.ParentOrderCommand cmd;
    private VwapMvpEngine.ParentOrder order;
    private VwapMvpEngine.EmissionMode emissionMode;

    @Setup(Level.Trial)
    public void setup() {
        emissionMode = VwapMvpEngine.EmissionMode.fromSystemPropertyOrThrow();
        engine = new VwapMvpEngine();
        sequencer = RealAeronIpcSequencer.launch("algo-jmh");
        samples = Long.getLong("javamvp.samples", DEFAULT_SAMPLES);
        recorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples));
        totalChildren = 0;
        wallStart = System.nanoTime();
        cmd = new SbeMessages.ParentOrderCommand();
        cmd.clientId = 42;
        cmd.symbolIndex = 1;
        cmd.side = 0;
        cmd.totalQuantity = 40_000;
        cmd.limitPrice = 15_000_000L;
        cmd.startTime = 0;
        cmd.endTime = 8_000_000;
        cmd.numBuckets = 12;
        cmd.participationRate = 0.12;
        cmd.minSliceSize = 100;
        cmd.maxSliceSize = 4_000;
        order = new VwapMvpEngine.ParentOrder();
        order.symbolIndex = 1;
        order.totalQuantity = 40_000;
        order.basePrice = 15_000_000L;
        order.startNs = 0;
        order.endNs = 8_000_000;
        order.tickStepNs = 50_000;
        order.numBuckets = 12;
        order.participationRate = 0.12;
        order.maxSliceSize = 4_000;
    }

    @Benchmark
    public long runBenchmark() {
        for (long i = 0; i < samples; i++) {
            cmd.sequenceId = i + 1;
            cmd.parentOrderId = i + 1;
            cmd.timestamp = i;

            byte[] sequenced = sequencer.sequenceRoundTrip(cmd.encode());
            SbeMessages.ParentOrderCommand decoded = SbeMessages.ParentOrderCommand.decode(sequenced);

            order.parentOrderId = decoded.parentOrderId;
            order.side = decoded.side == 0 ? Types.Side.BUY : Types.Side.SELL;
            order.leavesQuantity = decoded.totalQuantity;

            long started = System.nanoTime();
            List<SlicePayload> slices;
            if (emissionMode == VwapMvpEngine.EmissionMode.BATCH_BENCH) {
                slices = engine.generateSlices(order);
            } else {
                java.util.ArrayList<SlicePayload> out = new java.util.ArrayList<>();
                engine.resetRustParityState(order);
                final long processTimeNs = Math.max(order.startNs, order.endNs - 1);
                while (order.leavesQuantity > 0) {
                    SlicePayload slice = engine.generateSliceRustParity(order, processTimeNs);
                    if (slice == null) {
                        break;
                    }
                    out.add(slice);
                }
                slices = out;
            }
            long elapsed = System.nanoTime() - started;
            recorder.record(elapsed);
            totalChildren += slices.size();
        }
        return totalChildren;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        long wallElapsed = System.nanoTime() - wallStart;
        double sec = wallElapsed / 1_000_000_000.0;

        String json = "{\n"
            + "  \"bench\": \"algo_vwap_latency\",\n"
            + "  \"scenario\": \"algo_s1_steady\",\n"
            + "  \"samples\": " + samples + ",\n"
            + "  \"latency_ns_p90\": " + recorder.quantile(0.90) + ",\n"
            + "  \"latency_ns_p99\": " + recorder.quantile(0.99) + ",\n"
            + "  \"latency_ns_p999\": " + recorder.quantile(0.999) + ",\n"
            + "  \"throughput_parent_per_sec\": " + (samples / sec) + ",\n"
            + "  \"throughput_child_per_sec\": " + (totalChildren / sec) + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_vwap_latency_algo_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);
        sequencer.close();
    }
}
