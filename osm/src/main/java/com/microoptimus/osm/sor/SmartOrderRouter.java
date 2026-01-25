package com.microoptimus.osm.sor;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.OrderType;
import com.microoptimus.common.types.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SmartOrderRouter - Unified SOR for all order flows
 *
 * All order types (DMA, algo slices, principal quotes) flow through this SOR.
 * The SOR makes the routing decision for every order:
 * - INTERNAL: Route to internaliser for internal matching
 * - EXTERNAL: Route to liquidator for external venue execution
 *
 * Architecture:
 * - Gateway → Aeron Cluster → OSM (SOR)
 *     → INTERNAL: Internaliser
 *     → EXTERNAL: Liquidator → CME/Nasdaq/NYSE
 */
public class SmartOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRouter.class);

    // Routing components
    private final VenueScorer venueScorer;
    private final OrderSplitter orderSplitter;
    private final RiskManager riskManager;

    // Venue configurations
    private final VenueConfig[] venueConfigs;

    // Statistics
    private long ordersRouted;
    private long internalRoutes;
    private long externalRoutes;
    private long splitOrders;
    private long rejectedOrders;

    // Configuration
    private volatile boolean initialized;
    private long internalLiquidityThreshold = 1000; // Min qty to consider internal

    public SmartOrderRouter() {
        this.venueScorer = new VenueScorer();
        this.orderSplitter = new OrderSplitter();
        this.riskManager = new RiskManager();
        this.venueConfigs = new VenueConfig[VenueId.values().length];
        initializeDefaultConfigs();
    }

    /**
     * Initialize the SOR
     */
    public void initialize() {
        this.initialized = true;
        log.info("SmartOrderRouter initialized");
    }

    /**
     * Main routing decision method
     *
     * @param request The order request to route
     * @return Routing decision specifying target venue(s)
     */
    public RoutingDecision routeOrder(OrderRequest request) {
        if (!initialized) {
            return RoutingDecision.rejected(request.orderId, "SOR not initialized");
        }

        long startTime = System.nanoTime();
        ordersRouted++;

        try {
            // 1. Pre-trade risk checks
            RiskManager.RiskCheckResult riskResult = riskManager.checkOrder(request);
            if (!riskResult.isApproved()) {
                rejectedOrders++;
                return RoutingDecision.rejected(request.orderId, riskResult.getRejectReason());
            }

            // 2. Check internal liquidity availability
            boolean hasInternalLiquidity = checkInternalLiquidity(request);

            // 3. Score venues based on multiple factors
            VenueScore[] scores = venueScorer.scoreVenues(request, venueConfigs, hasInternalLiquidity);

            // 4. Make routing decision
            RoutingDecision decision = makeRoutingDecision(request, scores, hasInternalLiquidity);

            // 5. Update statistics
            updateStatistics(decision);

            return decision;

        } catch (Exception e) {
            log.error("Routing error for order {}: {}", request.orderId, e.getMessage());
            rejectedOrders++;
            return RoutingDecision.rejected(request.orderId, "Routing error: " + e.getMessage());
        } finally {
            long latency = System.nanoTime() - startTime;
            if (latency > 1_000_000) { // Log if > 1ms
                log.warn("SOR routing took {}ns for order {}", latency, request.orderId);
            }
        }
    }

    /**
     * Check if sufficient internal liquidity exists
     */
    private boolean checkInternalLiquidity(OrderRequest request) {
        // This would query the internaliser's orderbook
        // For now, use a simple heuristic
        return request.quantity <= internalLiquidityThreshold;
    }

    /**
     * Make routing decision based on venue scores
     */
    private RoutingDecision makeRoutingDecision(OrderRequest request, VenueScore[] scores,
                                                 boolean hasInternalLiquidity) {
        // Find best venue
        VenueScore bestScore = null;
        for (VenueScore score : scores) {
            if (score != null && (bestScore == null || score.totalScore > bestScore.totalScore)) {
                bestScore = score;
            }
        }

        if (bestScore == null) {
            return RoutingDecision.rejected(request.orderId, "No suitable venue");
        }

        // Check if order should be split
        if (request.quantity > bestScore.maxQuantity && request.quantity > internalLiquidityThreshold) {
            VenueAllocation[] allocations = orderSplitter.splitOrder(request, scores, venueConfigs);
            if (allocations.length > 1) {
                splitOrders++;
                return RoutingDecision.split(request.orderId, allocations);
            }
        }

        // Single venue routing
        if (bestScore.venueId == VenueId.INTERNAL) {
            return RoutingDecision.internal(request.orderId, request.quantity);
        } else {
            return RoutingDecision.external(request.orderId, bestScore.venueId, request.quantity);
        }
    }

    /**
     * Update routing statistics
     */
    private void updateStatistics(RoutingDecision decision) {
        switch (decision.getAction()) {
            case ROUTE_INTERNAL:
                internalRoutes++;
                break;
            case ROUTE_EXTERNAL:
                externalRoutes++;
                break;
            case SPLIT_ORDER:
                splitOrders++;
                break;
            case REJECT:
                rejectedOrders++;
                break;
        }
    }

    /**
     * Configure a venue
     */
    public void configureVenue(VenueId venueId, VenueConfig config) {
        venueConfigs[venueId.ordinal()] = config;
    }

    /**
     * Initialize default venue configurations
     */
    private void initializeDefaultConfigs() {
        // INTERNAL: Highest priority, zero latency, zero fees
        configureVenue(VenueId.INTERNAL, new VenueConfig(
                VenueId.INTERNAL, 100, true, 10_000_000, 0, 1.0, 0.0));

        // CME: High priority, low latency
        configureVenue(VenueId.CME, new VenueConfig(
                VenueId.CME, 90, true, 1_000_000, 150_000, 0.95, 0.0001));

        // NASDAQ: Good priority
        configureVenue(VenueId.NASDAQ, new VenueConfig(
                VenueId.NASDAQ, 85, true, 500_000, 200_000, 0.93, 0.0002));

        // NYSE: Lower priority, higher latency
        configureVenue(VenueId.NYSE, new VenueConfig(
                VenueId.NYSE, 80, true, 500_000, 250_000, 0.91, 0.0002));

        // ARCA
        configureVenue(VenueId.ARCA, new VenueConfig(
                VenueId.ARCA, 75, true, 300_000, 220_000, 0.90, 0.00025));

        // IEX: Lower priority due to speed bump
        configureVenue(VenueId.IEX, new VenueConfig(
                VenueId.IEX, 70, true, 200_000, 350_000, 0.88, 0.00015));
    }

    /**
     * Set internal liquidity threshold
     */
    public void setInternalLiquidityThreshold(long threshold) {
        this.internalLiquidityThreshold = threshold;
    }

    // Statistics getters
    public long getOrdersRouted() { return ordersRouted; }
    public long getInternalRoutes() { return internalRoutes; }
    public long getExternalRoutes() { return externalRoutes; }
    public long getSplitOrders() { return splitOrders; }
    public long getRejectedOrders() { return rejectedOrders; }

    public double getInternalRoutingRate() {
        return ordersRouted > 0 ? (internalRoutes * 100.0) / ordersRouted : 0.0;
    }

    @Override
    public String toString() {
        return String.format("SmartOrderRouter[routed=%d, internal=%d(%.1f%%), external=%d, split=%d, rejected=%d]",
                ordersRouted, internalRoutes, getInternalRoutingRate(),
                externalRoutes, splitOrders, rejectedOrders);
    }
}
