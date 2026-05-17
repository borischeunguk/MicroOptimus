package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.algo.VwapMvpEngine;
import com.microoptimus.javamvp.common.AeronStyleSequencer;
import com.microoptimus.javamvp.common.BenchmarkSupport;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;
import com.microoptimus.javamvp.common.Types;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class E2EAlgoSorLatencyBenchmark {
    private static final long SAMPLES = 1_000_000L;

    public static void main(String[] args) throws IOException {
        VwapMvpEngine.EmissionMode emissionMode = VwapMvpEngine.EmissionMode.fromSystemPropertyOrThrow();

        AeronStyleSequencer seqAlgoToSor = new AeronStyleSequencer();
        AeronStyleSequencer seqSorOut = new AeronStyleSequencer();
        MmapSharedRegion region = new MmapSharedRegion(Paths.get(".ipc", "javamvp_mmap_region.dat"), 1, 8 * 1024 * 1024);

        VwapMvpEngine algo = new VwapMvpEngine();
        SorMvpRouter router = new SorMvpRouter();

        BenchmarkSupport.LatencyRecorder parentRecorder = new BenchmarkSupport.LatencyRecorder((int) SAMPLES);
        BenchmarkSupport.LatencyRecorder childRecorder = new BenchmarkSupport.LatencyRecorder((int) SAMPLES * 4);

        long totalChildren = 0;
        long wallStart = System.nanoTime();

        for (long i = 0; i < SAMPLES; i++) {
            long parentStart = System.nanoTime();
            VwapMvpEngine.ParentOrder order = new VwapMvpEngine.ParentOrder();
            order.parentOrderId = i + 1;
            order.symbolIndex = 1;
            order.side = Types.Side.BUY;
            order.totalQuantity = 40_000;
            order.leavesQuantity = 40_000;
            order.basePrice = 1500;
            order.startNs = 0;
            order.endNs = 8_000_000;
            order.tickStepNs = 40_000;
            order.numBuckets = 12;
            order.participationRate = 0.12;
            order.maxSliceSize = 4_500;

            if (emissionMode == VwapMvpEngine.EmissionMode.BATCH_BENCH) {
                List<SlicePayload> slices = algo.generateSlices(order);
                for (SlicePayload slice : slices) {
                    ShmRef sliceRef = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());
                    SbeMessages.AlgoSliceRefEvent in = new SbeMessages.AlgoSliceRefEvent();
                    in.sequenceId = i + 1;
                    in.parentOrderId = slice.parentOrderId;
                    in.sliceId = slice.sliceId;
                    in.timestamp = slice.timestamp;
                    in.ref = sliceRef;
                    seqAlgoToSor.publish(in.encode());
                    totalChildren++;
                }
            } else {
                algo.resetRustParityState(order);
                final long processTimeNs = Math.max(order.startNs, order.endNs - 1);
                while (order.leavesQuantity > 0) {
                    SlicePayload slice = algo.generateSliceRustParity(order, processTimeNs);
                    if (slice == null) {
                        break;
                    }
                    ShmRef sliceRef = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());
                    SbeMessages.AlgoSliceRefEvent in = new SbeMessages.AlgoSliceRefEvent();
                    in.sequenceId = i + 1;
                    in.parentOrderId = slice.parentOrderId;
                    in.sliceId = slice.sliceId;
                    in.timestamp = slice.timestamp;
                    in.ref = sliceRef;
                    seqAlgoToSor.publish(in.encode());
                    totalChildren++;
                }
            }

            byte[] eventBytes;
            while ((eventBytes = seqAlgoToSor.poll()) != null) {
                SbeMessages.AlgoSliceRefEvent evt = SbeMessages.AlgoSliceRefEvent.decode(eventBytes);
                byte[] sliceBytes = region.read(evt.ref);
                SlicePayload slice = SlicePayload.decode(sliceBytes);

                long childStart = System.nanoTime();
                RouteDecisionPayload decision = router.route(slice);
                long childElapsed = System.nanoTime() - childStart;
                childRecorder.record(childElapsed);

                ShmRef routeRef = region.write(SbeMessages.TEMPLATE_SOR_ROUTE_REF, decision.encode());
                SbeMessages.SorRouteRefEvent out = new SbeMessages.SorRouteRefEvent();
                out.sequenceId = evt.sequenceId;
                out.parentOrderId = evt.parentOrderId;
                out.sliceId = evt.sliceId;
                out.routeId = decision.routeId;
                out.timestamp = decision.timestamp;
                out.ref = routeRef;
                seqSorOut.publish(out.encode());
            }

            while (seqSorOut.poll() != null) {
                // consume all routed outputs to keep queue bounded
            }

            long parentElapsed = System.nanoTime() - parentStart;
            parentRecorder.record(parentElapsed);
        }

        long wallElapsed = System.nanoTime() - wallStart;
        double sec = wallElapsed / 1_000_000_000.0;

        String json = "{\n"
            + "  \"bench\": \"e2e_algo_sor_latency\",\n"
            + "  \"scenario\": \"e2e_s1_steady\",\n"
            + "  \"samples\": " + SAMPLES + ",\n"
            + "  \"parent_latency_ns_p90\": " + parentRecorder.quantile(0.90) + ",\n"
            + "  \"parent_latency_ns_p99\": " + parentRecorder.quantile(0.99) + ",\n"
            + "  \"parent_latency_ns_p999\": " + parentRecorder.quantile(0.999) + ",\n"
            + "  \"child_latency_ns_p90\": " + childRecorder.quantile(0.90) + ",\n"
            + "  \"child_latency_ns_p99\": " + childRecorder.quantile(0.99) + ",\n"
            + "  \"child_latency_ns_p999\": " + childRecorder.quantile(0.999) + ",\n"
            + "  \"throughput_parent_per_sec\": " + (SAMPLES / sec) + ",\n"
            + "  \"throughput_child_per_sec\": " + (totalChildren / sec) + ",\n"
            + "  \"total_children\": " + totalChildren + "\n"
            + "}\n";

        Path out = Paths.get("perf-reports", "java_aeron_e2e_algo_sor_latency_e2e_s1_steady.json");
        BenchmarkSupport.writeJson(out, json);
        System.out.println("wrote " + out.toAbsolutePath());
    }
}

