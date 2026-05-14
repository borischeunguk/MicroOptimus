package com.microoptimus.javamvp.sor.jmh;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.RealAeronIpcSequencer;
import com.microoptimus.javamvp.common.SbeMessages;
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

@State(Scope.Benchmark)
public class RouterLatencyJmh {
    private static final long DEFAULT_SAMPLES = 1_000_000L;

    private SorMvpRouter router;
    private RealAeronIpcSequencer sequencer;
    private BenchmarkSupport.LatencyRecorder recorder;
    private long samples;
    private long wallStart;
    private SlicePayload slice;
    private SbeMessages.AlgoSliceRefEvent event;

    @Setup(Level.Trial)
    public void setup() {
        router = new SorMvpRouter();
        sequencer = RealAeronIpcSequencer.launch("sor-router-jmh");
        samples = Long.getLong("javamvp.samples", DEFAULT_SAMPLES);
        recorder = new BenchmarkSupport.LatencyRecorder((int) Math.min(Integer.MAX_VALUE, samples));
        wallStart = System.nanoTime();
        slice = new SlicePayload();
        slice.symbolIndex = 1;
        slice.side = Types.Side.BUY;
        slice.price = 1500;
        slice.sliceNumber = 1;
        event = new SbeMessages.AlgoSliceRefEvent();
    }

    @Benchmark
    public long runBenchmark() {
        long routeCount = 0;
        for (long i = 0; i < samples; i++) {
            slice.sliceId = i + 1;
            slice.parentOrderId = i + 1;
            slice.quantity = 200 + (i % 2000);
            slice.timestamp = i;

            event.sequenceId = i + 1;
            event.parentOrderId = slice.parentOrderId;
            event.sliceId = slice.sliceId;
            event.timestamp = slice.timestamp;
            event.ref = new com.microoptimus.javamvp.common.ShmRef(1, 2, 0, 0, i + 1);

            byte[] sequenced = sequencer.sequenceRoundTrip(event.encode());
            // just decode + route function time
            long started = System.nanoTime();
            SbeMessages.AlgoSliceRefEvent decoded = SbeMessages.AlgoSliceRefEvent.decode(sequenced);
            if (decoded.sliceId != slice.sliceId) {
                throw new IllegalStateException("sequencer mismatch");
            }

            RouteDecisionPayload decision = router.route(slice);
            long elapsed = System.nanoTime() - started;
            recorder.record(elapsed);
            routeCount += decision.routeId > 0 ? 1 : 0;
        }
        return routeCount;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        long wallElapsed = System.nanoTime() - wallStart;
        double sec = wallElapsed / 1_000_000_000.0;

        String json = "{\n"
            + "  \"bench\": \"sor_router_latency\",\n"
            + "  \"scenario\": \"sor_s1_steady\",\n"
            + "  \"samples\": " + samples + ",\n"
            + "  \"latency_ns_p90\": " + recorder.quantile(0.90) + ",\n"
            + "  \"latency_ns_p99\": " + recorder.quantile(0.99) + ",\n"
            + "  \"latency_ns_p999\": " + recorder.quantile(0.999) + ",\n"
            + "  \"throughput_routes_per_sec\": " + (samples / sec) + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_router_latency_sor_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);
        sequencer.close();
    }
}

