package com.microoptimus.javamvp.sor.md;

import com.microoptimus.javamvp.common.Types;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class SimulatedMarketDataService implements MarketDataService {
    private final Map<Integer, EnumMap<Types.VenueId, TopOfBook>> booksBySymbol = new HashMap<>();

    @Override
    public TopOfBook topOfBook(int symbolIndex, Types.VenueId venueId) {
        EnumMap<Types.VenueId, TopOfBook> byVenue = booksBySymbol.get(symbolIndex);
        if (byVenue == null) {
            return null;
        }
        return byVenue.get(venueId);
    }

    public void update(
        int symbolIndex,
        Types.VenueId venueId,
        double bidPx,
        long bidSize,
        double askPx,
        long askSize,
        long timestampNanos
    ) {
        EnumMap<Types.VenueId, TopOfBook> byVenue = booksBySymbol.computeIfAbsent(
            symbolIndex,
            ignored -> new EnumMap<>(Types.VenueId.class)
        );

        TopOfBook tob = byVenue.computeIfAbsent(venueId, ignored -> new TopOfBook());
        tob.bestBidPx = bidPx;
        tob.bestBidSize = bidSize;
        tob.bestAskPx = askPx;
        tob.bestAskSize = askSize;
        tob.timestampNanos = timestampNanos;
    }
}

