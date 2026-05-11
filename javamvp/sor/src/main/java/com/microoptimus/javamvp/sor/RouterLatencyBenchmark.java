package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.Types;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RouterLatencyBenchmark {
    private static final long SAMPLES = 1_000_000L;

    public static void main(String[] args) throws IOException {
        SorMvpRouter router = new SorMvpRouter();
        BenchmarkSupport.LatencyRecorder recorder = new BenchmarkSupport.LatencyRecorder((int) SAMPLES);

        long wallStart = System.nanoTime();
        for (long i = 0; i < SAMPLES; i++) {
            SlicePayload s = new SlicePayload();
            s.sliceId = i + 1;
            s.parentOrderId = i + 1;
            s.symbolIndex = 1;
            s.side = Types.Side.BUY;
            s.quantity = 200 + (i % 2000);
            s.price = 1500;
            s.sliceNumber = 1;
            s.timestamp = i;

            long started = System.nanoTime();
            RouteDecisionPayload d = router.route(s);
            long elapsed = System.nanoTime() - started;
            recorder.record(elapsed);
            if (d.routeId == 0) {
                throw new IllegalStateException("invalid route");
            }
        }
        long wallElapsed = System.nanoTime() - wallStart;
        double sec = wallElapsed / 1_000_000_000.0;

        String json = "{\n"
            + "  \"bench\": \"sor_router_latency\",\n"
            + "  \"scenario\": \"sor_s1_steady\",\n"
            + "  \"samples\": " + SAMPLES + ",\n"
            + "  \"latency_ns_p90\": " + recorder.quantile(0.90) + ",\n"
            + "  \"latency_ns_p99\": " + recorder.quantile(0.99) + ",\n"
            + "  \"latency_ns_p999\": " + recorder.quantile(0.999) + ",\n"
            + "  \"throughput_routes_per_sec\": " + (SAMPLES / sec) + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_router_latency_sor_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}

