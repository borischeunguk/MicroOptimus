use common::types::{RoutingAction, VenueId};

use crate::order_request::OrderRequest;
use crate::risk::{RiskCheckResult, RiskManager};
use crate::scorer::VenueScorer;
use crate::splitter::OrderSplitter;
use crate::venue::{VenueAllocation, VenueConfig};

/// Routing decision produced by the SOR.
///
/// Ported from Java `RoutingDecision.java`.
pub struct RoutingDecision {
    pub order_id: u64,
    pub action: RoutingAction,
    pub primary_venue: Option<VenueId>,
    pub allocations: Vec<VenueAllocation>,
    pub quantity: u64,
    pub reject_reason: Option<&'static str>,
}

impl RoutingDecision {
    pub fn internal(order_id: u64, quantity: u64) -> Self {
        Self {
            order_id,
            action: RoutingAction::RouteInternal,
            primary_venue: Some(VenueId::Internal),
            allocations: Vec::new(),
            quantity,
            reject_reason: None,
        }
    }

    pub fn external(order_id: u64, venue: VenueId, quantity: u64) -> Self {
        Self {
            order_id,
            action: RoutingAction::RouteExternal,
            primary_venue: Some(venue),
            allocations: Vec::new(),
            quantity,
            reject_reason: None,
        }
    }

    pub fn split(order_id: u64, allocations: Vec<VenueAllocation>) -> Self {
        let total_qty: u64 = allocations.iter().map(|a| a.quantity).sum();
        let primary = allocations
            .iter()
            .find(|a| a.priority == 1)
            .and_then(|a| a.venue_id)
            .or_else(|| allocations.first().and_then(|a| a.venue_id));
        Self {
            order_id,
            action: RoutingAction::SplitOrder,
            primary_venue: primary,
            allocations,
            quantity: total_qty,
            reject_reason: None,
        }
    }

    pub fn rejected(order_id: u64, reason: &'static str) -> Self {
        Self {
            order_id,
            action: RoutingAction::Reject,
            primary_venue: None,
            allocations: Vec::new(),
            quantity: 0,
            reject_reason: Some(reason),
        }
    }

    pub fn is_internal(&self) -> bool {
        self.action == RoutingAction::RouteInternal
    }

    pub fn is_external(&self) -> bool {
        self.action == RoutingAction::RouteExternal
    }

    pub fn is_split(&self) -> bool {
        self.action == RoutingAction::SplitOrder
    }

    pub fn is_rejected(&self) -> bool {
        self.action == RoutingAction::Reject
    }
}

/// SmartOrderRouter - Main routing logic.
///
/// Ported from Java `SmartOrderRouter.java`.
/// Scores venues, optionally splits orders, applies risk checks.
pub struct SmartOrderRouter {
    scorer: VenueScorer,
    splitter: OrderSplitter,
    risk_manager: RiskManager,
    venue_configs: [Option<VenueConfig>; VenueId::COUNT],
    internal_liquidity_threshold: u64,
    initialized: bool,

    // Statistics
    pub orders_routed: u64,
    pub internal_routes: u64,
    pub external_routes: u64,
    pub split_orders: u64,
    pub rejected_orders: u64,
}

impl SmartOrderRouter {
    pub fn new() -> Self {
        let mut sor = Self {
            scorer: VenueScorer::new(),
            splitter: OrderSplitter::new(),
            risk_manager: RiskManager::new(),
            venue_configs: [None; VenueId::COUNT],
            internal_liquidity_threshold: 1000,
            initialized: false,
            orders_routed: 0,
            internal_routes: 0,
            external_routes: 0,
            split_orders: 0,
            rejected_orders: 0,
        };
        sor.initialize_default_configs();
        sor
    }

    /// Initialize the SOR.
    pub fn initialize(&mut self) {
        self.initialized = true;
    }

    /// Main routing decision method.
    pub fn route_order(&mut self, request: &OrderRequest) -> RoutingDecision {
        if !self.initialized {
            return RoutingDecision::rejected(request.order_id, "SOR not initialized");
        }

        self.orders_routed += 1;

        // 1. Risk checks
        match self.risk_manager.check_order(request) {
            RiskCheckResult::Rejected(reason) => {
                self.rejected_orders += 1;
                return RoutingDecision::rejected(request.order_id, reason);
            }
            RiskCheckResult::Approved => {}
        }

        // 2. Check internal liquidity
        let has_internal = self.check_internal_liquidity(request);

        // 3. Score venues
        let scores = self
            .scorer
            .score_venues(request, &self.venue_configs, has_internal);

        // 4. Find best venue
        let best = scores.iter().filter(|s| s.is_valid()).max_by(|a, b| {
            a.total_score
                .partial_cmp(&b.total_score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        let best = match best {
            Some(s) => s,
            None => {
                self.rejected_orders += 1;
                return RoutingDecision::rejected(request.order_id, "No suitable venue");
            }
        };

        let best_venue_id = best.venue_id.unwrap();
        let best_max_qty = best.max_quantity;

        // 5. Check if split is needed
        if request.quantity > best_max_qty && request.quantity > self.internal_liquidity_threshold {
            let allocations = self.splitter.split_order(request, scores);
            if allocations.len() > 1 {
                self.split_orders += 1;
                return RoutingDecision::split(request.order_id, allocations);
            }
        }

        // 6. Single venue routing
        if best_venue_id == VenueId::Internal {
            self.internal_routes += 1;
            RoutingDecision::internal(request.order_id, request.quantity)
        } else {
            self.external_routes += 1;
            RoutingDecision::external(request.order_id, best_venue_id, request.quantity)
        }
    }

    fn check_internal_liquidity(&self, request: &OrderRequest) -> bool {
        request.quantity <= self.internal_liquidity_threshold
    }

    fn initialize_default_configs(&mut self) {
        // CME: low latency, high fill rate
        self.venue_configs[VenueId::Cme.index()] = Some(VenueConfig::new(
            VenueId::Cme,
            90,
            true,
            1_000_000,
            150_000,
            0.95,
            0.0001,
        ));

        // NASDAQ: slightly higher latency
        self.venue_configs[VenueId::Nasdaq.index()] = Some(VenueConfig::new(
            VenueId::Nasdaq,
            85,
            true,
            500_000,
            200_000,
            0.93,
            0.0002,
        ));
    }

    pub fn configure_venue(&mut self, config: VenueConfig) {
        self.venue_configs[config.venue_id.index()] = Some(config);
    }

    pub fn set_internal_liquidity_threshold(&mut self, threshold: u64) {
        self.internal_liquidity_threshold = threshold;
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

impl Default for SmartOrderRouter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::*;

    fn make_request(quantity: u64) -> OrderRequest {
        OrderRequest {
            sequence_id: 1,
            order_id: 42,
            client_id: 1,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            order_type: OrderType::Limit,
            price: 1_500, // price in ticks, value = qty * 1500
            quantity,
            time_in_force: TimeInForce::Ioc,
            flow_type: OrderFlowType::Dma,
            timestamp: 0,
        }
    }

    #[test]
    fn test_not_initialized() {
        let mut sor = SmartOrderRouter::new();
        // Don't call initialize
        let decision = sor.route_order(&make_request(100));
        assert!(decision.is_rejected());
    }

    #[test]
    fn test_route_to_best_venue() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();

        let decision = sor.route_order(&make_request(5000));
        assert!(decision.is_external());
        // CME should be selected (higher score)
        assert_eq!(decision.primary_venue, Some(VenueId::Cme));
        assert_eq!(decision.quantity, 5000);
    }

    #[test]
    fn test_route_internal_with_liquidity() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();

        // Add internal venue config
        sor.configure_venue(VenueConfig::new(
            VenueId::Internal,
            100,
            true,
            10_000_000,
            0,
            1.0,
            0.0,
        ));
        sor.set_internal_liquidity_threshold(5000);

        let decision = sor.route_order(&make_request(500));
        assert!(decision.is_internal());
    }

    #[test]
    fn test_risk_rejection() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();

        let decision = sor.route_order(&make_request(2_000_000));
        assert!(decision.is_rejected());
        assert_eq!(sor.rejected_orders, 1);
    }

    #[test]
    fn test_statistics() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();

        sor.route_order(&make_request(1000));
        sor.route_order(&make_request(2000));
        sor.route_order(&make_request(3000));

        assert_eq!(sor.orders_routed, 3);
        assert_eq!(sor.external_routes, 3);
    }

    #[test]
    fn test_order_splitting() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();
        // Set CME max to small so split is triggered
        sor.configure_venue(VenueConfig::new(
            VenueId::Cme,
            90,
            true,
            500,
            150_000,
            0.95,
            0.0001,
        ));
        sor.configure_venue(VenueConfig::new(
            VenueId::Nasdaq,
            85,
            true,
            500,
            200_000,
            0.93,
            0.0002,
        ));
        sor.set_internal_liquidity_threshold(100);

        let decision = sor.route_order(&make_request(800));
        // Both venues have max 500 each, order is 800 > best max (500)
        // Should split across both venues
        assert!(
            decision.is_split(),
            "expected split, got {:?}",
            decision.action
        );
        let total: u64 = decision.allocations.iter().map(|a| a.quantity).sum();
        assert_eq!(total, 800);
    }

    #[test]
    fn test_algo_slice_flow() {
        let mut sor = SmartOrderRouter::new();
        sor.initialize();

        let mut request = make_request(500);
        request.flow_type = OrderFlowType::AlgoSlice;
        request.parent_order_id = 100;

        let decision = sor.route_order(&request);
        assert!(!decision.is_rejected());
    }
}
