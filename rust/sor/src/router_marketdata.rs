use common::shm::MarketDataRegion;
use common::types::VenueId;

use crate::order_request::OrderRequest;
use crate::risk::{RiskCheckResult, RiskManager};
use crate::router::RoutingDecision;
use crate::venue::VenueConfig;

use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// Scoring weights for the market-data-aware combined score.
///
/// `price + latency + fees` weights must not necessarily sum to 1.0;
/// they are relative multipliers applied before summation.
#[derive(Clone, Copy)]
pub struct MdScoringWeights {
    /// Weight for price-impact score (how close limit_price is to best bid/ask).
    pub price: f64,
    /// Weight for latency score (lower latency ⇒ higher score).
    pub latency: f64,
    /// Weight for fee score (lower fees ⇒ higher score).
    pub fees: f64,
}

impl Default for MdScoringWeights {
    fn default() -> Self {
        Self { price: 0.5, latency: 0.3, fees: 0.2 }
    }
}

/// Per-venue static configuration used by `MarketDataRouter`.
///
/// Only CME and NASDAQ are supported; internal routing is handled by a
/// separate threshold check.
#[derive(Clone, Copy)]
struct VenueParams {
    venue_id: VenueId,
    avg_latency_ns: u64,
    fees_per_share: f64,
    enabled: bool,
    max_order_size: u64,
}

const DEFAULT_EXTERNAL_VENUES: [VenueParams; 2] = [
    VenueParams {
        venue_id: VenueId::Cme,
        avg_latency_ns: 130_000,
        fees_per_share: 0.00010,
        enabled: true,
        max_order_size: 1_500_000,
    },
    VenueParams {
        venue_id: VenueId::Nasdaq,
        avg_latency_ns: 170_000,
        fees_per_share: 0.00015,
        enabled: true,
        max_order_size: 1_200_000,
    },
];

/// SOR router that reads a **fresh market-data snapshot on every routing call**
/// and uses price-impact + latency + fees to select the best venue.
///
/// # Routing logic
/// For each enabled external venue, a combined score is computed:
///
/// ```text
/// price_score   = 1 / (|limit_price − best_bid_or_ask| + 1)
/// latency_score = REF_LATENCY_NS / venue_avg_latency_ns   (REF = 100_000)
/// fee_score     = 1 − fees_per_share
/// total         = w_price × price_score
///               + w_latency × latency_score
///               + w_fees × fee_score
/// ```
///
/// If no market-data snapshot exists yet for the symbol, `price_score` defaults
/// to 1.0 (best case) so routing falls back cleanly to latency + fees.
pub struct MarketDataRouter {
    md_region: MarketDataRegion,
    risk_manager: RiskManager,
    weights: MdScoringWeights,
    venue_params: [VenueParams; 2],
    internal_liquidity_threshold: u64,
    initialized: bool,

    // Statistics
    pub orders_routed: u64,
    pub internal_routes: u64,
    pub external_routes: u64,
    pub rejected_orders: u64,
}

#[inline]
fn epoch_ns() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("time went backwards")
        .as_nanos()
}

impl MarketDataRouter {
    /// Construct with default venue parameters and scoring weights.
    pub fn new(md_region: MarketDataRegion) -> Self {
        let mut router = Self {
            md_region,
            risk_manager: RiskManager::new(),
            weights: MdScoringWeights::default(),
            venue_params: DEFAULT_EXTERNAL_VENUES,
            internal_liquidity_threshold: 100,
            initialized: false,
            orders_routed: 0,
            internal_routes: 0,
            external_routes: 0,
            rejected_orders: 0,
        };
        router.initialized = true;
        router
    }

    /// Override scoring weights (e.g. in benchmarks / tests).
    pub fn with_weights(mut self, weights: MdScoringWeights) -> Self {
        self.weights = weights;
        self
    }

    /// Override a specific venue's parameters.
    pub fn configure_venue(&mut self, config: VenueConfig) {
        for p in self.venue_params.iter_mut() {
            if p.venue_id == config.venue_id {
                p.avg_latency_ns = config.avg_latency_ns;
                p.fees_per_share = config.fees_per_share;
                p.enabled = config.enabled;
                p.max_order_size = config.max_order_size;
                return;
            }
        }
    }

    pub fn set_internal_liquidity_threshold(&mut self, threshold: u64) {
        self.internal_liquidity_threshold = threshold;
    }

    /// Route an order using a **fresh** market-data snapshot for the symbol.
    ///
    /// Every call reads from `md_region` — no caching.
    pub fn route_order(&mut self, request: &OrderRequest) -> RoutingDecision {
        let start_wall_ns = epoch_ns();
        let start = Instant::now();
        eprintln!("Routing order {} (qty {}) for symbol {} request timestamp {} current timestamp {}",
            request.order_id, request.quantity, request.symbol_index, request.timestamp,  start_wall_ns);
        if !self.initialized {
            return RoutingDecision::rejected(request.order_id, "MarketDataRouter not initialized");
        }

        self.orders_routed += 1;

        // 1. Pre-trade risk check
        match self.risk_manager.check_order(request) {
            RiskCheckResult::Rejected(reason) => {
                self.rejected_orders += 1;
                return RoutingDecision::rejected(request.order_id, reason);
            }
            RiskCheckResult::Approved => {}
        }

        // 2. Small orders → internalize
        if request.quantity <= self.internal_liquidity_threshold {
            self.internal_routes += 1;
            return RoutingDecision::internal(request.order_id, request.quantity);
        }

        // 3. Read fresh market-data snapshot (unconditional, no caching)
        let snap = self.md_region.try_read_snapshot(request.symbol_index as usize);
        eprintln!("Snapshot for symbol {}: {:?}",
            request.symbol_index, snap.as_ref().map(|s| (s.bid_price, s.ask_price)));

        // 4. Score each enabled external venue
        let is_buy = matches!(request.side, common::types::Side::Buy);
        let mut best_venue = VenueId::Cme; // fallback
        let mut best_score = f64::NEG_INFINITY;

        for params in &self.venue_params {
            if !params.enabled || request.quantity > params.max_order_size {
                continue;
            }
            let score = self.compute_score(params, &snap, request.price, is_buy);
            if score > best_score {
                best_score = score;
                best_venue = params.venue_id;
            }
        }

        self.external_routes += 1;
        let end_wall_ns = epoch_ns();
        let dur_ns = start.elapsed().as_nanos();
        eprintln!("Routing order {} (qty {}) for symbol {} request timestamp {} end timestamp {} duration {}",
                  request.order_id, request.quantity, request.symbol_index, request.timestamp,  end_wall_ns, dur_ns );
        RoutingDecision::external(request.order_id, best_venue, request.quantity)
    }

    /// Combined score = α × price_score + β × latency_score + γ × fee_score.
    ///
    /// All three components are normalised to roughly [0, 1]:
    /// - `price_score`   = 1 / (price_impact_ticks + 1)    ∈ (0, 1]
    /// - `latency_score` = REF_NS / latency_ns              ∈ ~(0.6, 1.0] for typical venues
    /// - `fee_score`     = 1 − fees_per_share               ∈ ~[0.9999, 1.0]
    #[inline]
    fn compute_score(
        &self,
        params: &VenueParams,
        snap: &Option<common::sbe::MarketDataUpdate>,
        limit_price: u64,
        is_buy: bool,
    ) -> f64 {
        // Price score: how close our limit price is to the current best quote.
        let price_score = if let Some(s) = snap {
            let ref_price = if is_buy { s.bid_price } else { s.ask_price };
            let impact = (limit_price as i64 - ref_price as i64).unsigned_abs();
            1.0 / (impact as f64 + 1.0)
        } else {
            // No snapshot yet — treat as best-case (price impact = 0)
            1.0
        };

        // Latency score: normalised relative to a 100µs reference.
        const REF_LATENCY_NS: f64 = 100_000.0;
        let latency_score = REF_LATENCY_NS / params.avg_latency_ns.max(1) as f64;

        // Fee score: 1 − fee_rate (lower fees → closer to 1.0)
        let fee_score = 1.0 - params.fees_per_share;

        self.weights.price * price_score
            + self.weights.latency * latency_score
            + self.weights.fees * fee_score
    }

    pub fn risk_manager_mut(&mut self) -> &mut RiskManager {
        &mut self.risk_manager
    }

    pub fn internal_routing_rate(&self) -> f64 {
        if self.orders_routed > 0 {
            self.internal_routes as f64 * 100.0 / self.orders_routed as f64
        } else {
            0.0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::sbe::MarketDataUpdate;
    use common::shm::MD_MAX_SYMBOLS;
    use common::types::{OrderFlowType, OrderType, Side, TimeInForce};

    fn test_region() -> MarketDataRegion {
        let path = std::env::temp_dir()
            .join(format!("mo_md_router_test_{}.dat", std::process::id()))
            .to_string_lossy()
            .into_owned();
        MarketDataRegion::create(&path, MD_MAX_SYMBOLS)
    }

    fn make_request(quantity: u64) -> OrderRequest {
        OrderRequest {
            sequence_id: 1,
            order_id: 42,
            client_id: 1,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            order_type: OrderType::Limit,
            price: 1_500,
            quantity,
            time_in_force: TimeInForce::Ioc,
            flow_type: OrderFlowType::AlgoSlice,
            timestamp: 0,
        }
    }

    #[test]
    fn routes_without_snapshot() {
        let mut router = MarketDataRouter::new(test_region());
        let decision = router.route_order(&make_request(5_000));
        // No snapshot → falls back to latency+fees; should pick CME (lower latency)
        assert!(decision.is_external());
        assert_eq!(decision.primary_venue, Some(VenueId::Cme));
    }

    #[test]
    fn internalises_small_orders() {
        let mut router = MarketDataRouter::new(test_region());
        let decision = router.route_order(&make_request(50)); // below threshold (100)
        assert!(decision.is_internal());
    }

    #[test]
    fn routes_based_on_price_impact() {
        let path = std::env::temp_dir()
            .join(format!("mo_md_router_price_{}.dat", std::process::id()))
            .to_string_lossy()
            .into_owned();
        let mut region = MarketDataRegion::create(&path, MD_MAX_SYMBOLS);
        // Place a bid at 1_500 and ask at 1_501 — limit_price=1_500 has 0 impact on both.
        let snap = MarketDataUpdate {
            symbol_index: 0,
            bid_price: 1_500,
            ask_price: 1_501,
            last_price: 1_500,
            bid_size: 10_000,
            ask_size: 10_000,
            timestamp: 1,
            ..Default::default()
        };
        region.write_snapshot(0, &snap);
        let region_r = MarketDataRegion::open(&path, MD_MAX_SYMBOLS);
        let mut router = MarketDataRouter::new(region_r);
        let decision = router.route_order(&make_request(5_000));
        assert!(decision.is_external());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn rejects_oversized_order() {
        let mut router = MarketDataRouter::new(test_region());
        let decision = router.route_order(&make_request(2_000_000));
        assert!(decision.is_rejected());
    }
}

