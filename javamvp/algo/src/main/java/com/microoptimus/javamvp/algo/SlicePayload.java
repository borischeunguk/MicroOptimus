package com.microoptimus.javamvp.algo;

import com.microoptimus.javamvp.common.Types;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class SlicePayload {
    public long sliceId;
    public long parentOrderId;
    public int symbolIndex;
    public Types.Side side;
    public long quantity;
    public long price;
    public int sliceNumber;
    public long timestamp;

    public byte[] encode() {
        ByteBuffer bb = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(sliceId);
        bb.putLong(parentOrderId);
        bb.putInt(symbolIndex);
        bb.put((byte) (side == Types.Side.BUY ? 0 : 1));
        bb.put(new byte[3]);
        bb.putLong(quantity);
        bb.putLong(price);
        bb.putInt(sliceNumber);
        bb.putInt(0);
        bb.putLong(timestamp);
        return bb.array();
    }

    public static SlicePayload decode(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        SlicePayload s = new SlicePayload();
        s.sliceId = bb.getLong();
        s.parentOrderId = bb.getLong();
        s.symbolIndex = bb.getInt();
        s.side = bb.get() == 0 ? Types.Side.BUY : Types.Side.SELL;
        bb.position(bb.position() + 3);
        s.quantity = bb.getLong();
        s.price = bb.getLong();
        s.sliceNumber = bb.getInt();
        bb.getInt();
        s.timestamp = bb.getLong();
        return s;
    }
}

