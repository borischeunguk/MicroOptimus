package com.microoptimus.javamvp.sor.latency;

import com.microoptimus.javamvp.common.Types;

import java.util.EnumMap;

public final class VenueLatencyTelemetry {
    private final EnumMap<Types.VenueId, Long> p50Micros = new EnumMap<>(Types.VenueId.class);

    public VenueLatencyTelemetry() {
        p50Micros.put(Types.VenueId.CBOE, 35L);
        p50Micros.put(Types.VenueId.NASQ, 30L);
        p50Micros.put(Types.VenueId.NYSE, 40L);
    }

    public long p50Micros(Types.VenueId venueId) {
        Long latency = p50Micros.get(venueId);
        return latency == null ? 50L : latency;
    }

    public void updateP50Micros(Types.VenueId venueId, long micros) {
        p50Micros.put(venueId, micros);
    }
}

