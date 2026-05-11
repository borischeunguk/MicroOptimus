package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.Types;
import java.util.ArrayList;
import java.util.List;

public final class VwapMvpEngine {
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
}

