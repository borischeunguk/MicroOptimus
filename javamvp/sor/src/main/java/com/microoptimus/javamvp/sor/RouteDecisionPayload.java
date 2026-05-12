package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.common.Types;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RouteDecisionPayload {
    public long routeId;
    public long parentOrderId;
    public long sliceId;
    public Types.VenueId venueId;
    public long quantity;
    public long timestamp;
    public long processingLatencyNs;

    public byte[] encode() {
        ByteBuffer bb = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(routeId);
        bb.putLong(parentOrderId);
        bb.putLong(sliceId);
        bb.putInt(venueId.ordinal());
        bb.putInt(0);
        bb.putLong(quantity);
        bb.putLong(timestamp);
        bb.putLong(processingLatencyNs);
        return bb.array();
    }

    public static RouteDecisionPayload decode(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        RouteDecisionPayload d = new RouteDecisionPayload();
        d.routeId = bb.getLong();
        d.parentOrderId = bb.getLong();
        d.sliceId = bb.getLong();
        d.venueId = Types.VenueId.values()[bb.getInt()];
        bb.getInt();
        d.quantity = bb.getLong();
        d.timestamp = bb.getLong();
        d.processingLatencyNs = bb.getLong();
        return d;
    }
}

