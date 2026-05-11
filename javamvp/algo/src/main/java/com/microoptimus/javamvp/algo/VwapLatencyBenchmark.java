package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.Types;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class VwapLatencyBenchmark {
    private static final long SAMPLES = 1_000_000L;

    public static void main(String[] args) throws IOException {
        VwapMvpEngine engine = new VwapMvpEngine();
        BenchmarkSupport.LatencyRecorder recorder = new BenchmarkSupport.LatencyRecorder((int) SAMPLES);

        long totalChildren = 0;
        long wallStart = System.nanoTime();
        for (long i = 0; i < SAMPLES; i++) {
            VwapMvpEngine.ParentOrder order = new VwapMvpEngine.ParentOrder();
            order.parentOrderId = i + 1;
            order.symbolIndex = 1;
            order.side = Types.Side.BUY;
            order.totalQuantity = 40_000;
            order.leavesQuantity = 40_000;
            order.basePrice = 15_000_000L;
            order.startNs = 0;
            order.endNs = 8_000_000;
            order.tickStepNs = 50_000;
            order.numBuckets = 12;
            order.participationRate = 0.12;
            order.maxSliceSize = 4_000;

            long started = System.nanoTime();
            List<SlicePayload> slices = engine.generateSlices(order);
            long elapsed = System.nanoTime() - started;
            recorder.record(elapsed);
            totalChildren += slices.size();
        }
        long wallElapsed = System.nanoTime() - wallStart;

        double sec = wallElapsed / 1_000_000_000.0;
        String json = "{\n"
            + "  \"bench\": \"algo_vwap_latency\",\n"
            + "  \"scenario\": \"algo_s1_steady\",\n"
            + "  \"samples\": " + SAMPLES + ",\n"
            + "  \"latency_ns_p90\": " + recorder.quantile(0.90) + ",\n"
            + "  \"latency_ns_p99\": " + recorder.quantile(0.99) + ",\n"
            + "  \"latency_ns_p999\": " + recorder.quantile(0.999) + ",\n"
            + "  \"throughput_parent_per_sec\": " + (SAMPLES / sec) + ",\n"
            + "  \"throughput_child_per_sec\": " + (totalChildren / sec) + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_vwap_latency_algo_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}

