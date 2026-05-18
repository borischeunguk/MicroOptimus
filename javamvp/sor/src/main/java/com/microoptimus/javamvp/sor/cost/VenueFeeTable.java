package com.microoptimus.javamvp.sor.cost;

import com.microoptimus.javamvp.common.Types;

import java.util.EnumMap;

public final class VenueFeeTable {
    private final EnumMap<Types.VenueId, Double> takerFeeBps = new EnumMap<>(Types.VenueId.class);

    public VenueFeeTable() {
        takerFeeBps.put(Types.VenueId.CBOE, 0.30);
        takerFeeBps.put(Types.VenueId.NASQ, 0.32);
        takerFeeBps.put(Types.VenueId.NYSE, 0.31);
    }

    public double takerFeeBps(Types.VenueId venueId) {
        Double fee = takerFeeBps.get(venueId);
        return fee == null ? 0.35 : fee;
    }
}

