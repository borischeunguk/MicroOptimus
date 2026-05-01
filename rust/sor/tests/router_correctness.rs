use common::types::{OrderFlowType, OrderType, Side, TimeInForce, VenueId};
use sor::order_request::OrderRequest;
use sor::router::SmartOrderRouter;
use sor::venue::VenueConfig;

fn request(order_id: u64, qty: u64, price: u64) -> OrderRequest {
    OrderRequest {
        sequence_id: order_id,
        order_id,
        client_id: 9,
        parent_order_id: 300,
        symbol_index: 0,
        side: Side::Buy,
        order_type: OrderType::Limit,
        price,
        quantity: qty,
        time_in_force: TimeInForce::Ioc,
        flow_type: OrderFlowType::AlgoSlice,
        timestamp: order_id,
    }
}

#[test]
fn router_prefers_cme_with_better_latency_and_fill() {
    let mut router = SmartOrderRouter::new();
    router.initialize();

    router.configure_venue(VenueConfig::new(
        VenueId::Cme,
        90,
        true,
        1_000_000,
        120_000,
        0.96,
        0.0001,
    ));
    router.configure_venue(VenueConfig::new(
        VenueId::Nasdaq,
        85,
        true,
        1_000_000,
        190_000,
        0.90,
        0.0001,
    ));

    let decision = router.route_order(&request(1, 1_000, 1_500));
    assert!(decision.is_external());
    assert_eq!(decision.primary_venue, Some(VenueId::Cme));
}

#[test]
fn router_can_split_when_single_venue_capacity_is_too_small() {
    let mut router = SmartOrderRouter::new();
    router.initialize();

    router.configure_venue(VenueConfig::new(
        VenueId::Cme,
        90,
        true,
        1_200,
        130_000,
        0.94,
        0.0001,
    ));
    router.configure_venue(VenueConfig::new(
        VenueId::Nasdaq,
        85,
        true,
        1_200,
        170_000,
        0.92,
        0.0001,
    ));
    router.set_internal_liquidity_threshold(100);

    let decision = router.route_order(&request(2, 2_000, 1_500));
    assert!(decision.is_split());

    let total_qty: u64 = decision.allocations.iter().map(|a| a.quantity).sum();
    assert_eq!(total_qty, 2_000);
}

#[test]
fn router_rejects_oversized_request_by_risk_rules() {
    let mut router = SmartOrderRouter::new();
    router.initialize();

    let decision = router.route_order(&request(3, 10_000_000, 1_500));
    assert!(decision.is_rejected());
    assert!(decision.reject_reason.is_some());
}
