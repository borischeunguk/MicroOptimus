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

    public byte[] encode() {
        ByteBuffer bb = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(routeId);
        bb.putLong(parentOrderId);
        bb.putLong(sliceId);
        bb.putInt(venueId.ordinal());
        bb.putInt(0);
        bb.putLong(quantity);
        bb.putLong(timestamp);
        return bb.array();
    }
}

