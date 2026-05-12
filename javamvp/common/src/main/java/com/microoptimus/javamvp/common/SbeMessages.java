package com.microoptimus.javamvp.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class SbeMessages {
    private SbeMessages() {}

    public static final int TEMPLATE_PARENT_ORDER = 1;
    public static final int TEMPLATE_ALGO_SLICE_REF = 2;
    public static final int TEMPLATE_SOR_ROUTE_REF = 3;
    public static final int TEMPLATE_CONTROL = 4;

    public static final class ControlMessage {
        public long sequenceId;
        public int serviceId;
        public int command;

        public byte[] encode() {
            ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(sequenceId);
            bb.putInt(serviceId);
            bb.putInt(command);
            return bb.array();
        }

        public static ControlMessage decode(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            ControlMessage m = new ControlMessage();
            m.sequenceId = bb.getLong();
            m.serviceId = bb.getInt();
            m.command = bb.getInt();
            return m;
        }
    }

    public static final class ParentOrderCommand {
        public long sequenceId;
        public long parentOrderId;
        public long clientId;
        public int symbolIndex;
        public byte side;
        public long totalQuantity;
        public long limitPrice;
        public long startTime;
        public long endTime;
        public long timestamp;
        public int numBuckets;
        public double participationRate;
        public long minSliceSize;
        public long maxSliceSize;

        public byte[] encode() {
            ByteBuffer bb = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(sequenceId);
            bb.putLong(parentOrderId);
            bb.putLong(clientId);
            bb.putInt(symbolIndex);
            bb.put(side);
            bb.put(new byte[3]);
            bb.putLong(totalQuantity);
            bb.putLong(limitPrice);
            bb.putLong(startTime);
            bb.putLong(endTime);
            bb.putLong(timestamp);
            bb.putInt(numBuckets);
            bb.putInt(0);
            bb.putDouble(participationRate);
            bb.putLong(minSliceSize);
            bb.putLong(maxSliceSize);
            return bb.array();
        }

        public static ParentOrderCommand decode(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            ParentOrderCommand c = new ParentOrderCommand();
            c.sequenceId = bb.getLong();
            c.parentOrderId = bb.getLong();
            c.clientId = bb.getLong();
            c.symbolIndex = bb.getInt();
            c.side = bb.get();
            bb.position(bb.position() + 3);
            c.totalQuantity = bb.getLong();
            c.limitPrice = bb.getLong();
            c.startTime = bb.getLong();
            c.endTime = bb.getLong();
            c.timestamp = bb.getLong();
            c.numBuckets = bb.getInt();
            bb.getInt();
            c.participationRate = bb.getDouble();
            c.minSliceSize = bb.getLong();
            c.maxSliceSize = bb.getLong();
            return c;
        }
    }

    public static final class AlgoSliceRefEvent {
        public long sequenceId;
        public long parentOrderId;
        public long sliceId;
        public long timestamp;
        public ShmRef ref;

        public byte[] encode() {
            ByteBuffer bb = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(sequenceId);
            bb.putLong(parentOrderId);
            bb.putLong(sliceId);
            bb.putLong(timestamp);
            bb.putInt(ref.regionId);
            bb.putInt(ref.msgType);
            bb.putLong(ref.offset);
            bb.putInt(ref.length);
            bb.putInt(0);
            bb.putLong(ref.seq);
            bb.putLong(0);
            return bb.array();
        }

        public static AlgoSliceRefEvent decode(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            AlgoSliceRefEvent e = new AlgoSliceRefEvent();
            e.sequenceId = bb.getLong();
            e.parentOrderId = bb.getLong();
            e.sliceId = bb.getLong();
            e.timestamp = bb.getLong();
            int regionId = bb.getInt();
            int msgType = bb.getInt();
            long offset = bb.getLong();
            int len = bb.getInt();
            bb.getInt();
            long seq = bb.getLong();
            e.ref = new ShmRef(regionId, msgType, offset, len, seq);
            return e;
        }
    }

    public static final class SorRouteRefEvent {
        public long sequenceId;
        public long parentOrderId;
        public long sliceId;
        public long routeId;
        public long timestamp;
        public ShmRef ref;

        public byte[] encode() {
            ByteBuffer bb = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
            bb.putLong(sequenceId);
            bb.putLong(parentOrderId);
            bb.putLong(sliceId);
            bb.putLong(routeId);
            bb.putLong(timestamp);
            bb.putInt(ref.regionId);
            bb.putInt(ref.msgType);
            bb.putLong(ref.offset);
            bb.putInt(ref.length);
            bb.putInt(0);
            bb.putLong(ref.seq);
            return bb.array();
        }

        public static SorRouteRefEvent decode(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            SorRouteRefEvent e = new SorRouteRefEvent();
            e.sequenceId = bb.getLong();
            e.parentOrderId = bb.getLong();
            e.sliceId = bb.getLong();
            e.routeId = bb.getLong();
            e.timestamp = bb.getLong();
            int regionId = bb.getInt();
            int msgType = bb.getInt();
            long offset = bb.getLong();
            int len = bb.getInt();
            bb.getInt();
            long seq = bb.getLong();
            e.ref = new ShmRef(regionId, msgType, offset, len, seq);
            return e;
        }
    }
}
