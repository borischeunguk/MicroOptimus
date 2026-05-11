package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.Types;

public final class SorMvpRouter {
    private long nextRouteId = 1;

    public RouteDecisionPayload route(SlicePayload slice) {
        RouteDecisionPayload d = new RouteDecisionPayload();
        d.routeId = nextRouteId++;
        d.parentOrderId = slice.parentOrderId;
        d.sliceId = slice.sliceId;
        d.quantity = slice.quantity;
        d.timestamp = slice.timestamp;
        d.venueId = slice.quantity > 800 ? Types.VenueId.CME : Types.VenueId.NASQ;
        return d;
    }
}

