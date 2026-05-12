package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.CrossProcessAeronIpcTransport;
import com.microoptimus.javamvp.common.E2EIpcConfig;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;

import java.nio.file.Paths;

public final class SorE2eServiceMain {
    private SorE2eServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        String aeronDir = System.getProperty("javamvp.e2e.aeron.dir");
        String mmapPath = System.getProperty("javamvp.e2e.mmap.path", ".ipc/javamvp_mmap_jmh.dat");
        long timeoutNs = Long.getLong("javamvp.e2e.timeout.ns", E2EIpcConfig.DEFAULT_TIMEOUT_NS);
        long startupTimeoutNs = Long.getLong("javamvp.e2e.startup.timeout.ns", 60_000_000_000L);
        if (aeronDir == null || aeronDir.isBlank()) {
            throw new IllegalArgumentException("Missing -Djavamvp.e2e.aeron.dir");
        }

        SorMvpRouter router = new SorMvpRouter();
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
                RouteDecisionPayload decision = router.route(slice);
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
