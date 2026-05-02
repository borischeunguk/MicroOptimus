package com.microoptimus.algo.bench;

import com.microoptimus.algo.algorithms.VWAPAlgorithm;
import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;
import com.microoptimus.common.types.Side;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class AlgoVwapLatencyBenchmark {

    @State(Scope.Thread)
    public static class BenchState {

        @Param({"algo_s1_steady", "algo_s2_open_burst", "algo_s3_thin_liquidity", "algo_s4_large_parent"})
        public String scenarioId;

        Scenario scenario;
        Histogram histogram;
        long samples;
        long totalChildren;
        long wallStartNs;
        long seed;

        @Setup(Level.Trial)
        public void setup() {
            scenario = Scenario.fromId(scenarioId);
            histogram = new Histogram(1, 60_000_000_000L, 3);
            samples = 0;
            totalChildren = 0;
            wallStartNs = System.nanoTime();
            seed = 1;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            long elapsedNs = System.nanoTime() - wallStartNs;
            double elapsedSec = elapsedNs / 1_000_000_000.0;
            StringBuilder sb = new StringBuilder(320);
            sb.append("{\n");
            sb.append(BenchmarkIo.jsonLine("bench", "algo_vwap_latency", true));
            sb.append(BenchmarkIo.jsonLine("scenario", scenarioId, true));
            sb.append(BenchmarkIo.jsonLine("samples", samples, true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p90", histogram.getValueAtPercentile(90.0), true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p99", histogram.getValueAtPercentile(99.0), true));
            sb.append(BenchmarkIo.jsonLine("latency_ns_p999", histogram.getValueAtPercentile(99.9), true));
            sb.append(BenchmarkIo.jsonLine("throughput_parent_per_sec", round(samples / elapsedSec), true));
            sb.append(BenchmarkIo.jsonLine("throughput_child_per_sec", round(totalChildren / elapsedSec), false));
            sb.append("}\n");
            BenchmarkIo.writeReport("vwap_latency_" + scenarioId + ".json", sb.toString());
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    @Benchmark
    public long parentToChild(BenchState state) {
        long started = System.nanoTime();
        long children = runParentOrder(state.scenario, state.seed++);
        long elapsed = System.nanoTime() - started;
        state.histogram.recordValue(elapsed);
        state.samples++;
        state.totalChildren += children;
        return children;
    }

    private static long runParentOrder(Scenario scenario, long seed) {
        Lcg lcg = new Lcg(seed);

        AlgoOrder order = new AlgoOrder();
        order.init(
            seed,
            7000 + seed,
            0,
            Side.BUY,
            scenario.parentQty,
            scenario.basePrice,
            AlgoOrder.AlgorithmType.VWAP,
            scenario.startNs,
            scenario.endNs,
            scenario.startNs
        );

        AlgoParameters params = AlgoParameters.vwap(100, scenario.maxSliceSize, 12, scenario.participationRate);
        params.setSliceIntervalMs(0);
        order.setParameters(params);
        order.start(scenario.startNs);

        VWAPAlgorithm algorithm = new VWAPAlgorithm();
        algorithm.initialize(order);

        long children = 0;
        long now = scenario.startNs;

        while (now <= scenario.endNs && order.getLeavesQuantity() > 0) {
            long priceJitter = lcg.bounded(1000) - 500;
            long currentPrice = Math.max(1, scenario.basePrice + priceJitter);

            List<Slice> slices = algorithm.generateSlices(order, now, currentPrice);
            for (Slice slice : slices) {
                long qty = slice.getQuantity();
                if (qty > 0) {
                    children++;
                    order.onSliceFill(qty, currentPrice, now);
                    algorithm.onSliceExecution(order, slice, qty, currentPrice);
                }
            }

            now += scenario.tickStepNs;
        }

        if (order.getLeavesQuantity() > 0) {
            order.onSliceFill(order.getLeavesQuantity(), scenario.basePrice, scenario.endNs);
        }

        return children;
    }

    private static final class Scenario {
        final long parentQty;
        final long startNs;
        final long endNs;
        final long tickStepNs;
        final long basePrice;
        final long maxSliceSize;
        final double participationRate;

        private Scenario(long parentQty, long startNs, long endNs, long tickStepNs, long basePrice, long maxSliceSize, double participationRate) {
            this.parentQty = parentQty;
            this.startNs = startNs;
            this.endNs = endNs;
            this.tickStepNs = tickStepNs;
            this.basePrice = basePrice;
            this.maxSliceSize = maxSliceSize;
            this.participationRate = participationRate;
        }

        static Scenario fromId(String id) {
            switch (id) {
                case "algo_s1_steady":
                    return new Scenario(40_000, 0, 8_000_000, 50_000, 15_000_000, 4_000, 0.12);
                case "algo_s2_open_burst":
                    return new Scenario(60_000, 0, 6_000_000, 20_000, 15_010_000, 8_000, 0.18);
                case "algo_s3_thin_liquidity":
                    return new Scenario(30_000, 0, 10_000_000, 80_000, 14_990_000, 2_000, 0.08);
                case "algo_s4_large_parent":
                    return new Scenario(250_000, 0, 20_000_000, 40_000, 15_020_000, 12_000, 0.15);
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

