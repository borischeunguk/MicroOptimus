use algo::algo_order::{AlgoOrder, VwapParams};
use algo::vwap::VwapAlgorithm;
use common::types::{AlgorithmType, Side};

fn build_order(total_qty: u64, start_ns: u64, end_ns: u64) -> AlgoOrder {
    let mut order = AlgoOrder::default();
    order.init(
        101,
        88,
        0,
        Side::Buy,
        total_qty,
        15_000_000,
        AlgorithmType::Vwap,
        start_ns,
        end_ns,
        start_ns,
    );
    order.params = VwapParams {
        num_buckets: 10,
        participation_rate: 0.12,
        min_slice_size: 100,
        max_slice_size: 4_000,
        slice_interval_ns: 0,
    };
    order.start(start_ns);
    order
}

#[test]
fn vwap_child_quantity_matches_parent_quantity() {
    let mut order = build_order(30_000, 0, 8_000_000);
    let mut algo = VwapAlgorithm::new();
    algo.initialize(&order);

    let mut now = order.start_time;
    let mut total_child_qty = 0u64;

    while now <= order.end_time && order.leaves_quantity > 0 {
        let limit_price = order.limit_price;
        if let Some(slice_idx) = algo.generate_slice(&mut order, now, limit_price) {
            let child_qty = algo.get_slice(slice_idx).quantity;
            total_child_qty += child_qty;
            order.on_slice_fill(child_qty, limit_price, now);
            algo.on_slice_execution(child_qty);
        }
        now += 40_000;
    }

    if order.leaves_quantity > 0 {
        total_child_qty += order.leaves_quantity;
        order.on_slice_fill(order.leaves_quantity, order.limit_price, order.end_time);
    }

    assert_eq!(total_child_qty, 30_000);
    assert_eq!(order.leaves_quantity, 0);
}

#[test]
fn vwap_bucket_progress_is_monotonic() {
    let order = build_order(20_000, 0, 10_000_000);
    let mut algo = VwapAlgorithm::new();
    algo.initialize(&order);

    let mut last_bucket = 0usize;
    for now in (0..=10_000_000).step_by(100_000) {
        let _ = algo.should_generate_slice(&order, now);
        let current = algo.current_bucket();
        assert!(current >= last_bucket, "bucket moved backwards at {now}");
        last_bucket = current;
    }
}

#[test]
fn vwap_respects_max_slice_size_cap() {
    let mut order = build_order(50_000, 0, 8_000_000);
    order.params.max_slice_size = 2_500;

    let mut algo = VwapAlgorithm::new();
    algo.initialize(&order);

    let mut now = 0u64;
    while now <= order.end_time && order.leaves_quantity > 0 {
        let limit_price = order.limit_price;
        if let Some(slice_idx) = algo.generate_slice(&mut order, now, limit_price) {
            let child_qty = algo.get_slice(slice_idx).quantity;
            assert!(child_qty <= 2_500, "slice exceeded configured max size");
            order.on_slice_fill(child_qty, limit_price, now);
            algo.on_slice_execution(child_qty);
        }
        now += 50_000;
    }
}
