package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.Types;
import java.util.ArrayList;
import java.util.List;

public final class VwapMvpEngine {
    public static final String EMISSION_MODE_PROPERTY = "javamvp.algo.emission.mode";

    public enum EmissionMode {
        BATCH_BENCH,
        RUST_PARITY;

        public static EmissionMode fromSystemPropertyOrThrow() {
            String raw = System.getProperty(EMISSION_MODE_PROPERTY);
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                    "Missing required JVM property -D" + EMISSION_MODE_PROPERTY
                        + " (allowed: BATCH_BENCH,RUST_PARITY)");
            }
            try {
                return EmissionMode.valueOf(raw.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid -D" + EMISSION_MODE_PROPERTY + "=" + raw
                        + " (allowed: BATCH_BENCH,RUST_PARITY)",
                    e);
            }
        }
    }

    public static final class ParentOrder {
        public long parentOrderId;
        public int symbolIndex;
        public Types.Side side;
        public long totalQuantity;
        public long leavesQuantity;
        public long basePrice;
        public long startNs;
        public long endNs;
        public long tickStepNs;
        public int numBuckets;
        public double participationRate;
        public long maxSliceSize;

        // State used by RUST_PARITY mode.
        long nextSliceId = 1;
        int nextSliceNumber = 1;
        long nextEmissionTimeNs;
        boolean parityInitialized;
    }

    public List<SlicePayload> generateSlices(ParentOrder order) {
        List<SlicePayload> slices = new ArrayList<>();
        long now = order.startNs;
        long sliceId = 1;
        int sliceNumber = 1;

        while (now <= order.endNs && order.leavesQuantity > 0) {
            long target = Math.max(100, (long) (order.totalQuantity * order.participationRate / order.numBuckets));
            long qty = Math.min(order.leavesQuantity, Math.min(target, order.maxSliceSize));
            if (qty > 0) {
                SlicePayload s = new SlicePayload();
                s.sliceId = sliceId++;
                s.parentOrderId = order.parentOrderId;
                s.symbolIndex = order.symbolIndex;
                s.side = order.side;
                s.quantity = qty;
                s.price = order.basePrice;
                s.sliceNumber = sliceNumber++;
                s.timestamp = now;
                slices.add(s);
                order.leavesQuantity -= qty;
            }
            now += order.tickStepNs;
        }

        if (order.leavesQuantity > 0) {
            SlicePayload s = new SlicePayload();
            s.sliceId = sliceId;
            s.parentOrderId = order.parentOrderId;
            s.symbolIndex = order.symbolIndex;
            s.side = order.side;
            s.quantity = order.leavesQuantity;
            s.price = order.basePrice;
            s.sliceNumber = sliceNumber;
            s.timestamp = order.endNs;
            slices.add(s);
            order.leavesQuantity = 0;
        }

        return slices;
    }

    public void resetRustParityState(ParentOrder order) {
        order.nextSliceId = 1;
        order.nextSliceNumber = 1;
        order.nextEmissionTimeNs = order.startNs;
        order.parityInitialized = true;
    }

    public SlicePayload generateSliceRustParity(ParentOrder order, long currentTimeNs) {
        if (order.leavesQuantity <= 0 || currentTimeNs < order.startNs) {
            return null;
        }
        if (!order.parityInitialized) {
            resetRustParityState(order);
        }

        if (order.nextEmissionTimeNs <= order.endNs && currentTimeNs >= order.nextEmissionTimeNs) {
            long qty = nextSliceQuantity(order);
            long timestamp = order.nextEmissionTimeNs;
            order.nextEmissionTimeNs += order.tickStepNs;
            if (qty > 0) {
                return createSlice(order, qty, timestamp);
            }
        }

        if (currentTimeNs >= order.endNs && order.leavesQuantity > 0) {
            return createSlice(order, order.leavesQuantity, order.endNs);
        }

        return null;
    }

    private long nextSliceQuantity(ParentOrder order) {
        long target = Math.max(100L, (long) (order.totalQuantity * order.participationRate / order.numBuckets));
        return Math.min(order.leavesQuantity, Math.min(target, order.maxSliceSize));
    }

    private SlicePayload createSlice(ParentOrder order, long qty, long timestamp) {
        SlicePayload s = new SlicePayload();
        s.sliceId = order.nextSliceId++;
        s.parentOrderId = order.parentOrderId;
        s.symbolIndex = order.symbolIndex;
        s.side = order.side;
        s.quantity = qty;
        s.price = order.basePrice;
        s.sliceNumber = order.nextSliceNumber++;
        s.timestamp = timestamp;
        order.leavesQuantity -= qty;
        return s;
    }
}

