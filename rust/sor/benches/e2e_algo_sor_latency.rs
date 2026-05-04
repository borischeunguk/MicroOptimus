use std::fs;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use algo::algo_order::{AlgoOrder, VwapParams};
use algo::vwap::VwapAlgorithm;
use common::types::{AlgorithmType, OrderFlowType, OrderType, Side, TimeInForce, VenueId};
use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

use sor::order_request::OrderRequest;
use sor::router::SmartOrderRouter;
use sor::venue::VenueConfig;

const MIN_REPORT_SAMPLES: u64 = 1_000_000;

#[derive(Clone, Copy)]
struct Scenario {
    name: &'static str,
    parent_qty: u64,
    start_ns: u64,
    end_ns: u64,
    tick_step_ns: u64,
    base_price: u64,
    max_slice_size: u64,
    participation_rate: f64,
    cme_latency_ns: u64,
    nasdaq_latency_ns: u64,
    cme_fill_rate: f64,
    nasdaq_fill_rate: f64,
}

#[derive(Clone, Copy)]
struct Lcg {
    state: u64,
}

impl Lcg {
    fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    fn next_u64(&mut self) -> u64 {
        self.state = self
            .state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        self.state
    }

    fn bounded(&mut self, bound: u64) -> u64 {
        if bound == 0 {
            return 0;
        }
        self.next_u64() % bound
    }
}

#[derive(Serialize)]
struct E2eBenchReport {
    bench: &'static str,
    scenario: &'static str,
    samples: u64,
    parent_latency_ns_p90: u64,
    parent_latency_ns_p99: u64,
    parent_latency_ns_p999: u64,
    child_latency_ns_p90: u64,
    child_latency_ns_p99: u64,
    child_latency_ns_p999: u64,
    throughput_parent_per_sec: f64,
    throughput_child_per_sec: f64,
    total_children: u64,
}

fn scenarios() -> [Scenario; 4] {
    [
        Scenario {
            name: "e2e_s1_steady",
            parent_qty: 40_000,
            start_ns: 0,
            end_ns: 8_000_000,
            tick_step_ns: 40_000,
            base_price: 1_500,
            max_slice_size: 4_500,
            participation_rate: 0.12,
            cme_latency_ns: 130_000,
            nasdaq_latency_ns: 170_000,
            cme_fill_rate: 0.95,
            nasdaq_fill_rate: 0.92,
        },
        Scenario {
            name: "e2e_s2_open_burst",
            parent_qty: 55_000,
            start_ns: 0,
            end_ns: 6_000_000,
            tick_step_ns: 20_000,
            base_price: 1_505,
            max_slice_size: 8_000,
            participation_rate: 0.18,
            cme_latency_ns: 140_000,
            nasdaq_latency_ns: 180_000,
            cme_fill_rate: 0.94,
            nasdaq_fill_rate: 0.91,
        },
        Scenario {
            name: "e2e_s3_thin_liquidity",
            parent_qty: 25_000,
            start_ns: 0,
            end_ns: 10_000_000,
            tick_step_ns: 75_000,
            base_price: 1_495,
            max_slice_size: 2_500,
            participation_rate: 0.08,
            cme_latency_ns: 180_000,
            nasdaq_latency_ns: 220_000,
            cme_fill_rate: 0.86,
            nasdaq_fill_rate: 0.83,
        },
        Scenario {
            name: "e2e_s4_large_parent",
            parent_qty: 240_000,
            start_ns: 0,
            end_ns: 20_000_000,
            tick_step_ns: 30_000,
            base_price: 1_510,
            max_slice_size: 12_000,
            participation_rate: 0.15,
            cme_latency_ns: 150_000,
            nasdaq_latency_ns: 190_000,
            cme_fill_rate: 0.93,
            nasdaq_fill_rate: 0.90,
        },
    ]
}

fn configure_router(router: &mut SmartOrderRouter, scenario: Scenario) {
    router.configure_venue(VenueConfig::new(
        VenueId::Cme,
        90,
        true,
        1_500_000,
        scenario.cme_latency_ns,
        scenario.cme_fill_rate,
        0.00010,
    ));
    router.configure_venue(VenueConfig::new(
        VenueId::Nasdaq,
        85,
        true,
        1_200_000,
        scenario.nasdaq_latency_ns,
        scenario.nasdaq_fill_rate,
        0.00015,
    ));
    router.set_internal_liquidity_threshold(100);
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!("e2e_algo_sor_latency_{scenario}.json"))
}

fn write_report(
    scenario: Scenario,
    parent_hist: &Histogram<u64>,
    child_hist: &Histogram<u64>,
    samples: u64,
    total_children: u64,
    elapsed: Duration,
) {
    let report = E2eBenchReport {
        bench: "e2e_algo_sor_latency",
        scenario: scenario.name,
        samples,
        parent_latency_ns_p90: parent_hist.value_at_quantile(0.90),
        parent_latency_ns_p99: parent_hist.value_at_quantile(0.99),
        parent_latency_ns_p999: parent_hist.value_at_quantile(0.999),
        child_latency_ns_p90: child_hist.value_at_quantile(0.90),
        child_latency_ns_p99: child_hist.value_at_quantile(0.99),
        child_latency_ns_p999: child_hist.value_at_quantile(0.999),
        throughput_parent_per_sec: samples as f64 / elapsed.as_secs_f64(),
        throughput_child_per_sec: total_children as f64 / elapsed.as_secs_f64(),
        total_children,
    };

    let path = report_path(scenario.name);
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    if let Ok(json) = serde_json::to_string_pretty(&report) {
        if let Err(err) = fs::write(path, json) {
            eprintln!("failed to write e2e benchmark report: {err}");
        }
    }
}

fn effective_iters(iters: u64) -> u64 {
    iters.max(MIN_REPORT_SAMPLES)
}

fn run_parent_to_route(scenario: Scenario, seed: u64, router: &mut SmartOrderRouter) -> (Duration, u64, Histogram<u64>) {
    let mut rng = Lcg::new(seed);

    let mut order = AlgoOrder::default();
    order.init(
        seed,
        8000 + seed,
        0,
        Side::Buy,
        scenario.parent_qty,
        scenario.base_price,
        AlgorithmType::Vwap,
        scenario.start_ns,
        scenario.end_ns,
        scenario.start_ns,
    );
    order.params = VwapParams {
        num_buckets: 12,
        participation_rate: scenario.participation_rate,
        min_slice_size: 100,
        max_slice_size: scenario.max_slice_size,
        slice_interval_ns: 0,
    };
    order.start(scenario.start_ns);

    let mut vwap = VwapAlgorithm::new();
    vwap.initialize(&order);

    let mut child_hist = Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
        .expect("histogram bounds should be valid");

    let started = Instant::now();
    let mut now = scenario.start_ns;
    let mut total_children = 0u64;

    while now <= scenario.end_ns && order.leaves_quantity > 0 {
        let price_jitter = rng.bounded(1000) as i64 - 500;
        let current_price = (scenario.base_price as i64 + price_jitter).max(1) as u64;

        if let Some(slice_idx) = vwap.generate_slice(&mut order, now, current_price) {
            let slice = vwap.get_slice(slice_idx);
            let qty = slice.quantity;

            if qty > 0 {
                let request = OrderRequest {
                    sequence_id: seed,
                    order_id: slice.slice_id,
                    client_id: order.client_id,
                    parent_order_id: order.algo_order_id,
                    symbol_index: order.symbol_index,
                    side: slice.side,
                    order_type: OrderType::Limit,
                    price: slice.price,
                    quantity: qty,
                    time_in_force: TimeInForce::Ioc,
                    flow_type: OrderFlowType::AlgoSlice,
                    timestamp: now,
                };

                let route_start = Instant::now();
                let decision = router.route_order(black_box(&request));
                let route_elapsed = route_start.elapsed();
                let _ = child_hist.record(route_elapsed.as_nanos() as u64);

                black_box(decision.action);
                total_children += 1;

                order.on_slice_fill(qty, current_price, now);
                vwap.on_slice_execution(qty);
            }
        }

        now = now.saturating_add(scenario.tick_step_ns);
    }

    if order.leaves_quantity > 0 {
        order.on_slice_fill(order.leaves_quantity, scenario.base_price, scenario.end_ns);
    }

    (started.elapsed(), total_children, child_hist)
}

fn bench_e2e_latency(c: &mut Criterion) {
    let mut group = c.benchmark_group("e2e_algo_sor_latency");
    group.measurement_time(Duration::from_secs(5));
    group.warm_up_time(Duration::from_secs(2));

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(BenchmarkId::new("parent_to_routed_children", scenario.name), &scenario, |b, s| {
            b.iter_custom(|iters| {
                let samples = effective_iters(iters);
                let mut router = SmartOrderRouter::new();
                router.initialize();
                configure_router(&mut router, *s);

                let mut parent_hist = Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                    .expect("histogram bounds should be valid");
                let mut child_hist_agg = Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                    .expect("histogram bounds should be valid");
                let wall_start = Instant::now();
                let mut total_children = 0u64;

                for i in 0..samples {
                    let (elapsed, children, child_hist) = run_parent_to_route(*s, i + 1, &mut router);
                    let _ = parent_hist.record(elapsed.as_nanos() as u64);
                    let _ = child_hist_agg.add(&child_hist);
                    total_children += children;
                    black_box(children);
                }

                let wall_elapsed = wall_start.elapsed();
                write_report(*s, &parent_hist, &child_hist_agg, samples, total_children, wall_elapsed);
                wall_elapsed
            });
        });
    }

    group.finish();
}

criterion_group!(benches, bench_e2e_latency);
criterion_main!(benches);

