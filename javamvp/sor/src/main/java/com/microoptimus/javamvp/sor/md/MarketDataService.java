package com.microoptimus.javamvp.sor.md;

import com.microoptimus.javamvp.common.Types;

public interface MarketDataService {
    TopOfBook topOfBook(int symbolIndex, Types.VenueId venueId);
}

