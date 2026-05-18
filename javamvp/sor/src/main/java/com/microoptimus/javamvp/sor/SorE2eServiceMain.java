package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.CrossProcessAeronIpcTransport;
import com.microoptimus.javamvp.common.E2EIpcConfig;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;
import com.microoptimus.javamvp.common.Types;
import com.microoptimus.javamvp.sor.cost.VenueFeeTable;
import com.microoptimus.javamvp.sor.latency.VenueLatencyTelemetry;
import com.microoptimus.javamvp.sor.md.SimulatedMarketDataService;

import java.nio.file.Paths;
import java.util.function.Function;

public final class SorE2eServiceMain {
    private SorE2eServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        String aeronDir = System.getProperty("javamvp.e2e.aeron.dir");
        String mmapPath = System.getProperty("javamvp.e2e.mmap.path", ".ipc/javamvp_mmap_jmh.dat");
        long timeoutNs = Long.getLong("javamvp.e2e.timeout.ns", E2EIpcConfig.DEFAULT_TIMEOUT_NS);
        long startupTimeoutNs = Long.getLong("javamvp.e2e.startup.timeout.ns", 60_000_000_000L);
        String routerImpl = System.getProperty("javamvp.sor.router.impl", "mvp");
        if (aeronDir == null || aeronDir.isBlank()) {
            throw new IllegalArgumentException("Missing -Djavamvp.e2e.aeron.dir");
        }

        Function<SlicePayload, RouteDecisionPayload> routeFn;
        if ("new".equalsIgnoreCase(routerImpl)) {
            SimulatedMarketDataService marketData = new SimulatedMarketDataService();
            VenueFeeTable feeTable = new VenueFeeTable();
            VenueLatencyTelemetry latencyTelemetry = new VenueLatencyTelemetry();
            // Static TOB snapshot for benchmark symbols; stale is disabled for deterministic E2E runs.
            long now = System.nanoTime();
            for (int symbol = 1; symbol <= 16; symbol++) {
                marketData.update(symbol, Types.VenueId.CBOE, 100.00, 1200, 100.02, 1000, now);
                marketData.update(symbol, Types.VenueId.NASQ, 100.01, 1400, 100.01, 1400, now);
                marketData.update(symbol, Types.VenueId.NYSE, 99.99, 1000, 100.03, 900, now);
            }
            SorRouter router = new SorRouter(
                marketData,
                feeTable,
                latencyTelemetry,
                1.0,
                0.20,
                0.01,
                0.05,
                Long.MAX_VALUE
            );
            routeFn = router::route;
        } else if ("mvp".equalsIgnoreCase(routerImpl)) {
            SorMvpRouter router = new SorMvpRouter();
            routeFn = router::route;
        } else {
            throw new IllegalArgumentException("Unsupported -Djavamvp.sor.router.impl=" + routerImpl + " (expected: mvp|new)");
        }

        SbeMessages.SorRouteRefEvent out = new SbeMessages.SorRouteRefEvent();
        SbeMessages.ControlMessage ctrl = new SbeMessages.ControlMessage();
        MmapSharedRegion region = new MmapSharedRegion(Paths.get(mmapPath), 1, 8 * 1024 * 1024);

        try (CrossProcessAeronIpcTransport transport = CrossProcessAeronIpcTransport.connect(aeronDir);
             CrossProcessAeronIpcTransport.BlockingSubscription inSub = transport.addSubscription(E2EIpcConfig.STREAM_ALGO_TO_SOR);
             CrossProcessAeronIpcTransport.BlockingPublication outPub = transport.addPublication(E2EIpcConfig.STREAM_SOR_TO_COORD);
             CrossProcessAeronIpcTransport.BlockingSubscription controlIn = transport.addSubscription(E2EIpcConfig.STREAM_COORD_TO_SVC_CONTROL);
             CrossProcessAeronIpcTransport.BlockingPublication controlOut = transport.addPublication(E2EIpcConfig.STREAM_SVC_TO_COORD_CONTROL)) {

            ctrl.sequenceId = 1;
            ctrl.serviceId = E2EIpcConfig.SERVICE_SOR;
            ctrl.command = E2EIpcConfig.CONTROL_READY;
            controlOut.offerBlocking(ctrl.encode(), startupTimeoutNs);

            awaitStart(controlIn, startupTimeoutNs, E2EIpcConfig.SERVICE_SOR);


            while (true) {
                CrossProcessAeronIpcTransport.PollResult in = inSub.pollBlocking(startupTimeoutNs);
                SbeMessages.AlgoSliceRefEvent evt = SbeMessages.AlgoSliceRefEvent.decode(in.payload);

                if (evt.sequenceId < 0) {
                    break;
                }

                SlicePayload slice = SlicePayload.decode(region.read(evt.ref));
                long routeStart = System.nanoTime();
                RouteDecisionPayload decision = routeFn.apply(slice);
                decision.processingLatencyNs = System.nanoTime() - routeStart;

                ShmRef ref = region.write(SbeMessages.TEMPLATE_SOR_ROUTE_REF, decision.encode());
                out.sequenceId = evt.sequenceId;
                out.parentOrderId = decision.parentOrderId;
                out.sliceId = decision.sliceId;
                out.routeId = decision.routeId;
                out.timestamp = decision.timestamp;
                out.ref = ref;
                outPub.offerBlocking(out.encode(), timeoutNs);
            }
        } catch (CrossProcessAeronIpcTransport.TimeoutException e) {
            throw new IllegalStateException("SOR service timed out under backpressure", e);
        }
    }

    private static void awaitStart(
        CrossProcessAeronIpcTransport.BlockingSubscription controlIn,
        long timeoutNs,
        int serviceId
    ) throws CrossProcessAeronIpcTransport.TimeoutException {
        while (true) {
            SbeMessages.ControlMessage msg = SbeMessages.ControlMessage.decode(controlIn.pollBlocking(timeoutNs).payload);
            if (msg.serviceId == serviceId && msg.command == E2EIpcConfig.CONTROL_START) {
                return;
            }
        }
    }
}
