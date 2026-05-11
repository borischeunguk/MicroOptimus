package com.microoptimus.javamvp.algo.jmh;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.algo.VwapMvpEngine;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.RealAeronClusterSequencer;
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
    private RealAeronClusterSequencer sequencer;
    private BenchmarkSupport.LatencyRecorder recorder;
    private long samples;
    private long totalChildren;
    private long wallStart;

    @Setup(Level.Trial)
    public void setup() {
        engine = new VwapMvpEngine();
        sequencer = RealAeronClusterSequencer.launch("algo-jmh");
        samples = Long.getLong("javamvp.samples", DEFAULT_SAMPLES);
        recorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples));
        totalChildren = 0;
        wallStart = System.nanoTime();
    }

    @Benchmark
    public long runBenchmark() {
        for (long i = 0; i < samples; i++) {
            SbeMessages.ParentOrderCommand cmd = new SbeMessages.ParentOrderCommand();
            cmd.sequenceId = i + 1;
            cmd.parentOrderId = i + 1;
            cmd.clientId = 42;
            cmd.symbolIndex = 1;
            cmd.side = 0;
            cmd.totalQuantity = 40_000;
            cmd.limitPrice = 15_000_000L;
            cmd.startTime = 0;
            cmd.endTime = 8_000_000;
            cmd.timestamp = i;
            cmd.numBuckets = 12;
            cmd.participationRate = 0.12;
            cmd.minSliceSize = 100;
            cmd.maxSliceSize = 4_000;

            byte[] sequenced = sequencer.sequenceRoundTrip(cmd.encode());
            SbeMessages.ParentOrderCommand decoded = SbeMessages.ParentOrderCommand.decode(sequenced);

            VwapMvpEngine.ParentOrder order = new VwapMvpEngine.ParentOrder();
            order.parentOrderId = decoded.parentOrderId;
            order.symbolIndex = decoded.symbolIndex;
            order.side = decoded.side == 0 ? Types.Side.BUY : Types.Side.SELL;
            order.totalQuantity = decoded.totalQuantity;
            order.leavesQuantity = decoded.totalQuantity;
            order.basePrice = decoded.limitPrice;
            order.startNs = decoded.startTime;
            order.endNs = decoded.endTime;
            order.tickStepNs = 50_000;
            order.numBuckets = decoded.numBuckets;
            order.participationRate = decoded.participationRate;
            order.maxSliceSize = decoded.maxSliceSize;

            long started = System.nanoTime();
            List<SlicePayload> slices = engine.generateSlices(order);
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
