package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.CrossProcessAeronIpcTransport;
import com.microoptimus.javamvp.common.E2EIpcConfig;
import com.microoptimus.javamvp.common.MmapSharedRegion;
import com.microoptimus.javamvp.common.SbeMessages;
import com.microoptimus.javamvp.common.ShmRef;
import com.microoptimus.javamvp.common.Types;

import java.util.ArrayDeque;
import java.nio.file.Paths;
import java.util.List;

public final class AlgoE2eServiceMain {
    private static final long DEFAULT_TICK_STEP_NS = 40_000L;

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


            ArrayDeque<PendingParent> activeParents = new ArrayDeque<>();
            boolean stopRequested = false;
            while (!stopRequested) {
                byte[] payload;
                while ((payload = inSub.poll()) != null) {
                    SbeMessages.ParentOrderCommand cmd = SbeMessages.ParentOrderCommand.decode(payload);
                    if (cmd.sequenceId < 0) {
                        stopRequested = true;
                        break;
                    }

                    VwapMvpEngine.ParentOrder nextOrder = toParentOrder(cmd);
                    if (emissionMode == VwapMvpEngine.EmissionMode.BATCH_BENCH) {
                        List<SlicePayload> slices = engine.generateSlices(nextOrder);
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
                        engine.resetRustParityState(nextOrder);
                        activeParents.addLast(new PendingParent(cmd.sequenceId, nextOrder));
                    }
                }

                if (emissionMode == VwapMvpEngine.EmissionMode.RUST_PARITY && !activeParents.isEmpty()) {
                    int activeCount = activeParents.size();
                    for (int i = 0; i < activeCount; i++) {
                        PendingParent pending = activeParents.pollFirst();
                        if (pending == null) {
                            break;
                        }

                        VwapMvpEngine.ParentOrder parent = pending.order;
                        long processTimeNs = Math.max(parent.startNs, parent.endNs - 1);
                        SlicePayload slice = engine.generateSliceRustParity(parent, processTimeNs);
                        if (slice != null) {
                            ShmRef ref = region.write(SbeMessages.TEMPLATE_ALGO_SLICE_REF, slice.encode());
                            out.sequenceId = pending.sequenceId;
                            out.parentOrderId = slice.parentOrderId;
                            out.sliceId = slice.sliceId;
                            out.timestamp = slice.timestamp;
                            out.ref = ref;
                            outPub.offerBlocking(out.encode(), timeoutNs);
                        }

                        if (parent.leavesQuantity > 0) {
                            activeParents.addLast(pending);
                        }
                    }
                }

                stdHintSpin();
            }
        } catch (CrossProcessAeronIpcTransport.TimeoutException e) {
            throw new IllegalStateException("Algo service timed out under backpressure", e);
        }
    }

    private static VwapMvpEngine.ParentOrder toParentOrder(SbeMessages.ParentOrderCommand cmd) {
        VwapMvpEngine.ParentOrder order = new VwapMvpEngine.ParentOrder();
        order.parentOrderId = cmd.parentOrderId;
        order.symbolIndex = cmd.symbolIndex;
        order.side = cmd.side == 0 ? Types.Side.BUY : Types.Side.SELL;
        order.totalQuantity = cmd.totalQuantity;
        order.leavesQuantity = cmd.totalQuantity;
        order.basePrice = cmd.limitPrice;
        order.startNs = cmd.startTime;
        order.endNs = cmd.endTime;
        order.tickStepNs = DEFAULT_TICK_STEP_NS;
        order.numBuckets = cmd.numBuckets;
        order.participationRate = cmd.participationRate;
        order.maxSliceSize = cmd.maxSliceSize;
        return order;
    }

    private static void stdHintSpin() {
        Thread.onSpinWait();
    }

    private static final class PendingParent {
        private final long sequenceId;
        private final VwapMvpEngine.ParentOrder order;

        private PendingParent(long sequenceId, VwapMvpEngine.ParentOrder order) {
            this.sequenceId = sequenceId;
            this.order = order;
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

