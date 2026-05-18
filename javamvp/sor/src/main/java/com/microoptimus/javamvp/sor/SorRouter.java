package com.microoptimus.javamvp.sor;

import com.microoptimus.javamvp.algo.SlicePayload;
import com.microoptimus.javamvp.common.Types;
import com.microoptimus.javamvp.sor.cost.VenueFeeTable;
import com.microoptimus.javamvp.sor.latency.VenueLatencyTelemetry;
import com.microoptimus.javamvp.sor.md.MarketDataService;
import com.microoptimus.javamvp.sor.md.TopOfBook;

public final class SorRouter {
    private static final Types.VenueId[] VENUES = {
        Types.VenueId.CBOE,
        Types.VenueId.NASQ,
        Types.VenueId.NYSE
    };

    private final MarketDataService marketDataService;
    private final VenueFeeTable feeTable;
    private final VenueLatencyTelemetry latencyTelemetry;

    private final double wPrice;
    private final double wFee;
    private final double wLatency;
    private final double wSize;
    private final long staleThresholdNanos;

    private long nextRouteId = 1;

    public SorRouter(
        MarketDataService marketDataService,
        VenueFeeTable feeTable,
        VenueLatencyTelemetry latencyTelemetry
    ) {
        this(marketDataService, feeTable, latencyTelemetry, 1.0, 0.20, 0.01, 0.05, 5_000_000L);
    }

    public SorRouter(
        MarketDataService marketDataService,
        VenueFeeTable feeTable,
        VenueLatencyTelemetry latencyTelemetry,
        double wPrice,
        double wFee,
        double wLatency,
        double wSize,
        long staleThresholdNanos
    ) {
        this.marketDataService = marketDataService;
        this.feeTable = feeTable;
        this.latencyTelemetry = latencyTelemetry;
        this.wPrice = wPrice;
        this.wFee = wFee;
        this.wLatency = wLatency;
        this.wSize = wSize;
        this.staleThresholdNanos = staleThresholdNanos;
    }

    public RouteDecisionPayload route(SlicePayload slice) {
        long now = System.nanoTime();

        Types.VenueId bestVenue = null;
        TopOfBook bestTob = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Types.VenueId venueId : VENUES) {
            TopOfBook tob = marketDataService.topOfBook(slice.symbolIndex, venueId);
            if (tob == null || isStale(now, tob)) {
                continue;
            }

            double executablePx = executablePrice(slice.side, tob);
            long executableSize = executableSize(slice.side, tob);
            if (executablePx <= 0.0 || executableSize <= 0) {
                continue;
            }

            double score = computeScore(slice, venueId, executablePx, executableSize);
            if (bestVenue == null || score > bestScore || (score == bestScore && tieBreak(slice.side, venueId, tob, bestVenue, bestTob))) {
                bestVenue = venueId;
                bestTob = tob;
                bestScore = score;
            }
        }

        RouteDecisionPayload decision = new RouteDecisionPayload();
        decision.routeId = nextRouteId++;
        decision.parentOrderId = slice.parentOrderId;
        decision.sliceId = slice.sliceId;
        decision.quantity = slice.quantity;
        decision.timestamp = slice.timestamp;
        decision.venueId = bestVenue == null ? Types.VenueId.NASQ : bestVenue;
        return decision;
    }

    private boolean isStale(long now, TopOfBook tob) {
        return now - tob.timestampNanos > staleThresholdNanos;
    }

    private double executablePrice(Types.Side side, TopOfBook tob) {
        return side == Types.Side.BUY ? tob.bestAskPx : tob.bestBidPx;
    }

    private long executableSize(Types.Side side, TopOfBook tob) {
        return side == Types.Side.BUY ? tob.bestAskSize : tob.bestBidSize;
    }

    private double computeScore(SlicePayload slice, Types.VenueId venueId, double px, long availableSize) {
        double priceTerm = slice.side == Types.Side.BUY ? -px : px;
        double feeTerm = feeTable.takerFeeBps(venueId);
        double latencyTerm = latencyTelemetry.p50Micros(venueId);
        double sizeTerm = Math.min(1.0, (double) availableSize / Math.max(1L, slice.quantity));
        return (wPrice * priceTerm) - (wFee * feeTerm) - (wLatency * latencyTerm) + (wSize * sizeTerm);
    }

    private boolean tieBreak(
        Types.Side side,
        Types.VenueId candidateVenue,
        TopOfBook candidateTob,
        Types.VenueId currentVenue,
        TopOfBook currentTob
    ) {
        if (currentTob == null) {
            return true;
        }

        double candidatePx = executablePrice(side, candidateTob);
        double currentPx = executablePrice(side, currentTob);
        if (side == Types.Side.BUY && candidatePx < currentPx) {
            return true;
        }
        if (side == Types.Side.SELL && candidatePx > currentPx) {
            return true;
        }

        long candidateSize = executableSize(side, candidateTob);
        long currentSize = executableSize(side, currentTob);
        if (candidateSize > currentSize) {
            return true;
        }

        long candidateLatency = latencyTelemetry.p50Micros(candidateVenue);
        long currentLatency = latencyTelemetry.p50Micros(currentVenue);
        if (candidateLatency < currentLatency) {
            return true;
        }

        return candidateVenue.ordinal() < currentVenue.ordinal();
    }
}

