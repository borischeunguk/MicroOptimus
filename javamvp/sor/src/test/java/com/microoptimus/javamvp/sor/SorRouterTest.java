package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.Types;
import com.microoptimus.javamvp.sor.cost.VenueFeeTable;
import com.microoptimus.javamvp.sor.latency.VenueLatencyTelemetry;
import com.microoptimus.javamvp.sor.md.SimulatedMarketDataService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class SorRouterTest {

    @Test
    void routesBuyToBestAskVenue() {
        SimulatedMarketDataService md = new SimulatedMarketDataService();
        VenueFeeTable fees = new VenueFeeTable();
        VenueLatencyTelemetry latency = new VenueLatencyTelemetry();
        SorRouter router = new SorRouter(md, fees, latency);

        long now = System.nanoTime();
        int symbol = 11;
        md.update(symbol, Types.VenueId.CBOE, 99.99, 600, 100.03, 1000, now);
        md.update(symbol, Types.VenueId.NASQ, 100.00, 600, 100.02, 800, now);
        md.update(symbol, Types.VenueId.NYSE, 99.98, 600, 99.70, 1200, now);

        SlicePayload slice = new SlicePayload();
        slice.parentOrderId = 1;
        slice.sliceId = 2;
        slice.symbolIndex = symbol;
        slice.side = Types.Side.BUY;
        slice.quantity = 500;
        slice.timestamp = now;

        RouteDecisionPayload decision = router.route(slice);
        Assertions.assertEquals(Types.VenueId.NYSE, decision.venueId);
    }

    @Test
    void routesSellToBestBidVenue() {
        SimulatedMarketDataService md = new SimulatedMarketDataService();
        VenueFeeTable fees = new VenueFeeTable();
        VenueLatencyTelemetry latency = new VenueLatencyTelemetry();
        SorRouter router = new SorRouter(md, fees, latency);

        long now = System.nanoTime();
        int symbol = 12;
        md.update(symbol, Types.VenueId.CBOE, 100.01, 700, 100.03, 600, now);
        md.update(symbol, Types.VenueId.NASQ, 100.02, 700, 100.04, 700, now);
        md.update(symbol, Types.VenueId.NYSE, 99.99, 900, 100.01, 900, now);

        SlicePayload slice = new SlicePayload();
        slice.parentOrderId = 3;
        slice.sliceId = 4;
        slice.symbolIndex = symbol;
        slice.side = Types.Side.SELL;
        slice.quantity = 650;
        slice.timestamp = now;

        RouteDecisionPayload decision = router.route(slice);
        Assertions.assertEquals(Types.VenueId.NASQ, decision.venueId);
    }

    @Test
    void ignoresStaleQuotes() {
        SimulatedMarketDataService md = new SimulatedMarketDataService();
        VenueFeeTable fees = new VenueFeeTable();
        VenueLatencyTelemetry latency = new VenueLatencyTelemetry();
        SorRouter router = new SorRouter(md, fees, latency);

        long now = System.nanoTime();
        int symbol = 13;
        md.update(symbol, Types.VenueId.CBOE, 100.00, 1000, 100.00, 1000, now - 20_000_000L);
        md.update(symbol, Types.VenueId.NASQ, 99.98, 800, 100.02, 900, now);
        md.update(symbol, Types.VenueId.NYSE, 99.97, 900, 100.03, 900, now);

        SlicePayload slice = new SlicePayload();
        slice.parentOrderId = 5;
        slice.sliceId = 6;
        slice.symbolIndex = symbol;
        slice.side = Types.Side.BUY;
        slice.quantity = 400;
        slice.timestamp = now;

        RouteDecisionPayload decision = router.route(slice);
        Assertions.assertNotEquals(Types.VenueId.CBOE, decision.venueId);
    }
}

