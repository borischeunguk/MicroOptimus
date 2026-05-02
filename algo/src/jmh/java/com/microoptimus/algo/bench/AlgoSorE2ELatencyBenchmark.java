package com.microoptimus.algo.bench;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventPoller;
import com.lmax.disruptor.EventPoller.PollState;
import com.lmax.disruptor.RingBuffer;
import com.microoptimus.algo.algorithms.VWAPAlgorithm;
import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class AlgoSorE2ELatencyBenchmark {

    @State(Scope.Thread)
    public static class BenchState {

        @Param({"e2e_s1_steady", "e2e_s2_open_burst", "e2e_s3_thin_liquidity", "e2e_s4_large_parent"})
        public String scenarioId;

        Scenario scenario;
        Histogram parentHistogram;
        Histogram childHistogram;
        long parentSamples;
        long totalChildren;
        long wallStartNs;
        long seed;

        SmartOrderRouterJavaFallback sor;
        RingBuffer<ChildOrderEvent> ringBuffer;
        EventPoller<ChildOrderEvent> poller;

        @Setup(Level.Trial)
        public void setup() {
            scenario = Scenario.fromId(scenarioId);
            parentHistogram = new Histogram(1, 60_000_000_000L, 3);
            childHistogram = new Histogram(1, 60_000_000_000L, 3);
            parentSamples = 0;
            totalChildren = 0;
            wallStartNs = System.nanoTime();
            seed = 1;

            sor = new SmartOrderRouterJavaFallback();
            sor.initialize("/tmp/sor_java_fallback.conf", "/tmp/sor_java_fallback.bin");

            ringBuffer = RingBuffer.createSingleProducer(ChildOrderEvent::new, 4096, new BusySpinWaitStrategy());
            poller = ringBuffer.newPoller();
            ringBuffer.addGatingSequences(poller.getSequence());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            long elapsedNs = System.nanoTime() - wallStartNs;
            double elapsedSec = elapsedNs / 1_000_000_000.0;
            StringBuilder sb = new StringBuilder(440);
            sb.append("{\n");
            sb.append(BenchmarkIo.jsonLine("bench", "e2e_algo_sor_latency", true));
            sb.append(BenchmarkIo.jsonLine("scenario", scenarioId, true));
            sb.append(BenchmarkIo.jsonLine("samples", parentSamples, true));
            sb.append(BenchmarkIo.jsonLine("parent_latency_ns_p90", parentHistogram.getValueAtPercentile(90.0), true));
            sb.append(BenchmarkIo.jsonLine("parent_latency_ns_p99", parentHistogram.getValueAtPercentile(99.0), true));
            sb.append(BenchmarkIo.jsonLine("parent_latency_ns_p999", parentHistogram.getValueAtPercentile(99.9), true));
            sb.append(BenchmarkIo.jsonLine("child_latency_ns_p90", childHistogram.getValueAtPercentile(90.0), true));
            sb.append(BenchmarkIo.jsonLine("child_latency_ns_p99", childHistogram.getValueAtPercentile(99.0), true));
            sb.append(BenchmarkIo.jsonLine("child_latency_ns_p999", childHistogram.getValueAtPercentile(99.9), true));
            sb.append(BenchmarkIo.jsonLine("throughput_parent_per_sec", round(parentSamples / elapsedSec), true));
            sb.append(BenchmarkIo.jsonLine("throughput_child_per_sec", round(totalChildren / elapsedSec), true));
            sb.append(BenchmarkIo.jsonLine("total_children", totalChildren, false));
            sb.append("}\n");
            BenchmarkIo.writeReport("e2e_algo_sor_latency_" + scenarioId + ".json", sb.toString());
            sor.shutdown();
        }

        private double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    @Benchmark
    public long parentToRoutedChildren(BenchState state) throws Exception {
        long started = System.nanoTime();
        long children = runParentToRoute(state);
        long elapsed = System.nanoTime() - started;
        state.parentHistogram.recordValue(elapsed);
        state.parentSamples++;
        state.totalChildren += children;
        return children;
    }

    private static long runParentToRoute(BenchState state) throws Exception {
        Scenario scenario = state.scenario;
        Lcg lcg = new Lcg(state.seed++);

        AlgoOrder order = new AlgoOrder();
        order.init(
            state.seed,
            8000 + state.seed,
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

        long now = scenario.startNs;
        long children = 0;
        long pending = 0;

        while (now <= scenario.endNs && order.getLeavesQuantity() > 0) {
            long priceJitter = lcg.bounded(1000) - 500;
            long currentPrice = Math.max(1, scenario.basePrice + priceJitter);
            List<Slice> slices = algorithm.generateSlices(order, now, currentPrice);

            for (Slice slice : slices) {
                long qty = slice.getQuantity();
                if (qty <= 0) {
                    continue;
                }

                long normalizedQty = normalizeQtyForCmeNasdaq(qty, children);
                publish(state.ringBuffer, slice, normalizedQty, currentPrice, now);
                children++;
                pending++;

                order.onSliceFill(qty, currentPrice, now);
                algorithm.onSliceExecution(order, slice, qty, currentPrice);
            }

            pending = drainAndRoute(state, pending);
            now += scenario.tickStepNs;
        }

        pending = drainAndRoute(state, pending);

        if (order.getLeavesQuantity() > 0) {
            order.onSliceFill(order.getLeavesQuantity(), scenario.basePrice, scenario.endNs);
        }

        return children;
    }

    private static long drainAndRoute(BenchState state, long pending) throws Exception {
        while (pending > 0) {
            final long[] processed = {0};
            PollState pollState = state.poller.poll((event, sequence, endOfBatch) -> {
                SmartOrderRouter.OrderRequest request = new SmartOrderRouter.OrderRequest(
                    event.sliceId,
                    "AAPL",
                    event.side,
                    OrderType.LIMIT,
                    event.price,
                    event.quantity,
                    event.timestamp,
                    "ALGO"
                );
                long routeStart = System.nanoTime();
                SmartOrderRouter.RoutingDecision decision = state.sor.routeOrder(request);
                long routeElapsed = System.nanoTime() - routeStart;
                state.childHistogram.recordValue(routeElapsed);

                // Rust parity scope: no synthetic venue ack return latency.
                if (decision.primaryVenue != SmartOrderRouter.VenueType.CME &&
                    decision.primaryVenue != SmartOrderRouter.VenueType.NASDAQ) {
                    throw new IllegalStateException("unexpected venue in benchmark: " + decision.primaryVenue);
                }

                processed[0]++;
                return true;
            });
            if (pollState == PollState.IDLE || processed[0] == 0) {
                break;
            }
            pending -= processed[0];
        }
        return pending;
    }

    private static void publish(RingBuffer<ChildOrderEvent> ringBuffer, Slice slice, long qty, long price, long timestamp) {
        long sequence = ringBuffer.next();
        try {
            ChildOrderEvent event = ringBuffer.get(sequence);
            event.sliceId = slice.getSliceId();
            event.parentOrderId = slice.getParentAlgoOrderId();
            event.side = slice.getSide();
            event.quantity = qty;
            event.price = price;
            event.timestamp = timestamp;
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    private static long normalizeQtyForCmeNasdaq(long qty, long seq) {
        if ((seq & 1L) == 0) {
            return Math.max(100, Math.min(900, qty));
        }
        return Math.max(1000, Math.min(9000, qty));
    }

    private static final class ChildOrderEvent {
        long sliceId;
        long parentOrderId;
        Side side;
        long quantity;
        long price;
        long timestamp;
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
                case "e2e_s1_steady":
                    return new Scenario(40_000, 0, 8_000_000, 40_000, 1_500, 4_500, 0.12);
                case "e2e_s2_open_burst":
                    return new Scenario(55_000, 0, 6_000_000, 20_000, 1_505, 8_000, 0.18);
                case "e2e_s3_thin_liquidity":
                    return new Scenario(25_000, 0, 10_000_000, 75_000, 1_495, 2_500, 0.08);
                case "e2e_s4_large_parent":
                    return new Scenario(240_000, 0, 20_000_000, 30_000, 1_510, 12_000, 0.15);
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

