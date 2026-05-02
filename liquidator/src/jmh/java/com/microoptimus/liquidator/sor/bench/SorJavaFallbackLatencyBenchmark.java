package com.microoptimus.liquidator.sor.bench;

import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.Side;
import com.microoptimus.liquidator.sor.SmartOrderRouter;
import com.microoptimus.liquidator.sor.SmartOrderRouterJavaFallback;
import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SorJavaFallbackLatencyBenchmark {

    @State(Scope.Thread)
    public static class BenchState {

        @Param({"sor_s1_steady", "sor_s2_open_burst", "sor_s3_thin_liquidity", "sor_s4_large_parent_children"})
        public String scenarioId;

        Scenario scenario;
        SmartOrderRouterJavaFallback router;
        Histogram histogram;
        long samples;
        long wallStartNs;
        Lcg lcg;
        long sequence;

        @Setup(Level.Trial)
        public void setup() {
            scenario = Scenario.fromId(scenarioId);
            router = new SmartOrderRouterJavaFallback();
            router.initialize("/tmp/sor_java_fallback.conf", "/tmp/sor_java_fallback.bin");
            histogram = new Histogram(1, 60_000_000_000L, 3);
            samples = 0;
            wallStartNs = System.nanoTime();
            lcg = new Lcg(99);
            sequence = 1;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            long elapsedNs = System.nanoTime() - wallStartNs;
            double elapsedSec = elapsedNs / 1_000_000_000.0;
            StringBuilder sb = new StringBuilder(320);
            sb.append("{\n");
            sb.append(BenchmarkIo.jsonLine("bench", "sor_router_latency", true));
            sb.append(BenchmarkIo.jsonLine("scenario", scenarioId, true));
            sb.append(BenchmarkIo.jsonLine("samples", samples, true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p90", histogram.getValueAtPercentile(90.0), true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p99", histogram.getValueAtPercentile(99.0), true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p999", histogram.getValueAtPercentile(99.9), true));
            sb.append(BenchmarkIo.jsonLine("throughput_routes_per_sec", round(samples / elapsedSec), false));
            sb.append("}\n");
            BenchmarkIo.writeReport("router_latency_" + scenarioId + ".json", sb.toString());
            router.shutdown();
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    @Benchmark
    public SmartOrderRouter.RoutingAction childRoute(BenchState state) {
        long qty = state.scenario.minQty + state.lcg.bounded(state.scenario.qtySpan);
        qty = normalizeQtyForCmeNasdaq(qty, state.sequence++);

        SmartOrderRouter.OrderRequest request = new SmartOrderRouter.OrderRequest(
            state.sequence,
            "AAPL",
            (state.sequence & 1L) == 0 ? Side.BUY : Side.SELL,
            OrderType.LIMIT,
            state.scenario.price,
            qty,
            state.sequence,
            "SOR_BENCH"
        );

        long started = System.nanoTime();
        SmartOrderRouter.RoutingDecision decision = state.router.routeOrder(request);
        long elapsed = System.nanoTime() - started;

        state.histogram.recordValue(elapsed);
        state.samples++;

        if (decision.primaryVenue != SmartOrderRouter.VenueType.CME &&
            decision.primaryVenue != SmartOrderRouter.VenueType.NASDAQ) {
            throw new IllegalStateException("unexpected venue in benchmark: " + decision.primaryVenue);
        }

        return decision.action;
    }

    private static long normalizeQtyForCmeNasdaq(long qty, long seq) {
        if ((seq & 1L) == 0) {
            return Math.max(100, Math.min(900, qty));
        }
        return Math.max(1000, Math.min(9000, qty));
    }

    private static final class Scenario {
        final long minQty;
        final long qtySpan;
        final long price;

        private Scenario(long minQty, long qtySpan, long price) {
            this.minQty = minQty;
            this.qtySpan = qtySpan;
            this.price = price;
        }

        static Scenario fromId(String id) {
            switch (id) {
                case "sor_s1_steady":
                    return new Scenario(200, 2_000, 1_500);
                case "sor_s2_open_burst":
                    return new Scenario(300, 4_000, 1_505);
                case "sor_s3_thin_liquidity":
                    return new Scenario(200, 5_000, 1_495);
                case "sor_s4_large_parent_children":
                    return new Scenario(500, 8_000, 1_510);
                default:
                    throw new IllegalArgumentException("unknown scenario: " + id);
            }
        }
    }

    private static final class Lcg {
        private long state;

        private Lcg(long seed) {
            this.state = seed;
        }

        private long next() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return state;
        }

        private long bounded(long bound) {
            if (bound == 0) {
                return 0;
            }
            return Long.remainderUnsigned(next(), bound);
        }
    }
}

