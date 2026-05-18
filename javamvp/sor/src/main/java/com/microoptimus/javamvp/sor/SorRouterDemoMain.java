package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.Types;
import com.microoptimus.javamvp.sor.cost.VenueFeeTable;
import com.microoptimus.javamvp.sor.latency.VenueLatencyTelemetry;
import com.microoptimus.javamvp.sor.md.SimulatedMarketDataService;

public final class SorRouterDemoMain {
    private SorRouterDemoMain() {
    }

    public static void main(String[] args) {
        SimulatedMarketDataService marketData = new SimulatedMarketDataService();
        VenueFeeTable feeTable = new VenueFeeTable();
        VenueLatencyTelemetry latency = new VenueLatencyTelemetry();
        SorRouter router = new SorRouter(marketData, feeTable, latency);

        long now = System.nanoTime();
        int symbol = 101;
        marketData.update(symbol, Types.VenueId.CBOE, 100.00, 1200, 100.02, 900, now);
        marketData.update(symbol, Types.VenueId.NASQ, 100.01, 1000, 100.03, 1400, now);
        marketData.update(symbol, Types.VenueId.NYSE, 99.99, 1500, 100.01, 500, now);

        SlicePayload buy = new SlicePayload();
        buy.parentOrderId = 1;
        buy.sliceId = 10;
        buy.symbolIndex = symbol;
        buy.side = Types.Side.BUY;
        buy.quantity = 600;
        buy.price = 10001;
        buy.sliceNumber = 1;
        buy.timestamp = now;

        SlicePayload sell = new SlicePayload();
        sell.parentOrderId = 2;
        sell.sliceId = 11;
        sell.symbolIndex = symbol;
        sell.side = Types.Side.SELL;
        sell.quantity = 600;
        sell.price = 10000;
        sell.sliceNumber = 1;
        sell.timestamp = now;

        RouteDecisionPayload buyDecision = router.route(buy);
        RouteDecisionPayload sellDecision = router.route(sell);

        System.out.println("BUY routed to: " + buyDecision.venueId);
        System.out.println("SELL routed to: " + sellDecision.venueId);
    }
}

