package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.CrossProcessAeronIpcTransport;
import com.microoptimus.javamvp.common.E2EIpcConfig;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;
import com.microoptimus.javamvp.common.Types;

import java.nio.file.Paths;
import java.util.List;

public final class AlgoE2eServiceMain {
    private AlgoE2eServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        String aeronDir = System.getProperty("javamvp.e2e.aeron.dir");
        String mmapPath = System.getProperty("javamvp.e2e.mmap.path", ".ipc/javamvp_mmap_jmh.dat");
        long timeoutNs = Long.getLong("javamvp.e2e.timeout.ns", E2EIpcConfig.DEFAULT_TIMEOUT_NS);
        long startupTimeoutNs = Long.getLong("javamvp.e2e.startup.timeout.ns", 60_000_000_000L);
        if (aeronDir == null || aeronDir.isBlank()) {
            throw new IllegalArgumentException("Missing -Djavamvp.e2e.aeron.dir");
        }
        VwapMvpEngine.EmissionMode emissionMode = VwapMvpEngine.EmissionMode.fromSystemPropertyOrThrow();

        VwapMvpEngine engine = new VwapMvpEngine();
        VwapMvpEngine.ParentOrder order = new VwapMvpEngine.ParentOrder();
        order.symbolIndex = 1;
        order.totalQuantity = 40_000;
        order.basePrice = 1500;
        order.startNs = 0;
        order.endNs = 8_000_000;
        order.tickStepNs = 40_000;
        order.numBuckets = 12;
        order.participationRate = 0.12;
        order.maxSliceSize = 4_500;

        SbeMessages.AlgoSliceRefEvent out = new SbeMessages.AlgoSliceRefEvent();
        SbeMessages.ControlMessage ctrl = new SbeMessages.ControlMessage();

        MmapSharedRegion region = new MmapSharedRegion(Paths.get(mmapPath), 1, 8 * 1024 * 1024);
        try (CrossProcessAeronIpcTransport transport = CrossProcessAeronIpcTransport.connect(aeronDir);
             CrossProcessAeronIpcTransport.BlockingSubscription inSub = transport.addSubscription(E2EIpcConfig.STREAM_COORD_TO_ALGO);
             CrossProcessAeronIpcTransport.BlockingPublication outPub = transport.addPublication(E2EIpcConfig.STREAM_ALGO_TO_SOR);
             CrossProcessAeronIpcTransport.BlockingSubscription controlIn = transport.addSubscription(E2EIpcConfig.STREAM_COORD_TO_SVC_CONTROL);
             CrossProcessAeronIpcTransport.BlockingPublication controlOut = transport.addPublication(E2EIpcConfig.STREAM_SVC_TO_COORD_CONTROL)) {

            ctrl.sequenceId = 1;
            ctrl.serviceId = E2EIpcConfig.SERVICE_ALGO;
            ctrl.command = E2EIpcConfig.CONTROL_READY;
            controlOut.offerBlocking(ctrl.encode(), startupTimeoutNs);

            awaitStart(controlIn, startupTimeoutNs, E2EIpcConfig.SERVICE_ALGO);


            while (true) {
                CrossProcessAeronIpcTransport.PollResult in = inSub.pollBlocking(startupTimeoutNs);
                SbeMessages.ParentOrderCommand cmd = SbeMessages.ParentOrderCommand.decode(in.payload);
                if (cmd.sequenceId < 0) {
                    break;
                }

                order.parentOrderId = cmd.parentOrderId;
                order.side = cmd.side == 0 ? Types.Side.BUY : Types.Side.SELL;
                order.leavesQuantity = cmd.totalQuantity;

                if (emissionMode == VwapMvpEngine.EmissionMode.BATCH_BENCH) {
                    List<SlicePayload> slices = engine.generateSlices(order);
                    for (SlicePayload slice : slices) {
                        ShmRef ref = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());
                        out.sequenceId = cmd.sequenceId;
                        out.parentOrderId = slice.parentOrderId;
                        out.sliceId = slice.sliceId;
                        out.timestamp = slice.timestamp;
                        out.ref = ref;
                        outPub.offerBlocking(out.encode(), timeoutNs);
                    }
                } else {
                    engine.resetRustParityState(order);
                    final long processTimeNs = Math.max(order.startNs, order.endNs - 1);
                    while (order.leavesQuantity > 0) {
                        SlicePayload slice = engine.generateSliceRustParity(order, processTimeNs);
                        if (slice == null) {
                            break;
                        }

                        ShmRef ref = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());
                        out.sequenceId = cmd.sequenceId;
                        out.parentOrderId = slice.parentOrderId;
                        out.sliceId = slice.sliceId;
                        out.timestamp = slice.timestamp;
                        out.ref = ref;
                        outPub.offerBlocking(out.encode(), timeoutNs);
                    }
                }
            }
        } catch (CrossProcessAeronIpcTransport.TimeoutException e) {
            throw new IllegalStateException("Algo service timed out under backpressure", e);
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

