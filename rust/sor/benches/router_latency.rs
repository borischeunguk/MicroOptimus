use std::fs;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::types::{OrderFlowType, OrderType, Side, TimeInForce, VenueId};
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
    min_qty: u64,
    qty_span: u64,
    price: u64,
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
struct BenchReport {
    bench: &'static str,
    scenario: &'static str,
    samples: u64,
    latency_ns_p90: u64,
    latency_ns_p99: u64,
    latency_ns_p999: u64,
    throughput_routes_per_sec: f64,
}

fn scenarios() -> [Scenario; 4] {
    [
        Scenario {
            name: "sor_s1_steady",
            min_qty: 200,
            qty_span: 2_000,
            price: 1_500,
            cme_latency_ns: 130_000,
            nasdaq_latency_ns: 170_000,
            cme_fill_rate: 0.95,
            nasdaq_fill_rate: 0.92,
        },
        Scenario {
            name: "sor_s2_open_burst",
            min_qty: 300,
            qty_span: 4_000,
            price: 1_505,
            cme_latency_ns: 140_000,
            nasdaq_latency_ns: 180_000,
            cme_fill_rate: 0.94,
            nasdaq_fill_rate: 0.90,
        },
        Scenario {
            name: "sor_s3_thin_liquidity",
            min_qty: 200,
            qty_span: 5_000,
            price: 1_495,
            cme_latency_ns: 180_000,
            nasdaq_latency_ns: 210_000,
            cme_fill_rate: 0.86,
            nasdaq_fill_rate: 0.83,
        },
        Scenario {
            name: "sor_s4_large_parent_children",
            min_qty: 500,
            qty_span: 8_000,
            price: 1_510,
            cme_latency_ns: 150_000,
            nasdaq_latency_ns: 190_000,
            cme_fill_rate: 0.93,
            nasdaq_fill_rate: 0.90,
        },
    ]
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!("router_latency_{scenario}.json"))
}

fn write_report(scenario: Scenario, hist: &Histogram<u64>, samples: u64, elapsed: Duration) {
    let report = BenchReport {
        bench: "sor_router_latency",
        scenario: scenario.name,
        samples,
        latency_ns_p90: hist.value_at_quantile(0.90),
        latency_ns_p99: hist.value_at_quantile(0.99),
        latency_ns_p999: hist.value_at_quantile(0.999),
        throughput_routes_per_sec: samples as f64 / elapsed.as_secs_f64(),
    };

    let path = report_path(scenario.name);
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    if let Ok(json) = serde_json::to_string_pretty(&report) {
        if let Err(err) = fs::write(path, json) {
            eprintln!("failed to write router benchmark report: {err}");
        }
    }
}

fn effective_iters(iters: u64) -> u64 {
    iters.max(MIN_REPORT_SAMPLES)
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

fn make_request(seed: u64, scenario: Scenario, rng: &mut Lcg) -> OrderRequest {
    let qty = scenario.min_qty + rng.bounded(scenario.qty_span).max(1);
    OrderRequest {
        sequence_id: seed,
        order_id: 20_000 + seed,
        client_id: 5,
        parent_order_id: 1000 + seed,
        symbol_index: 0,
        side: if rng.bounded(2) == 0 { Side::Buy } else { Side::Sell },
        order_type: OrderType::Limit,
        price: scenario.price,
        quantity: qty,
        time_in_force: TimeInForce::Ioc,
        flow_type: OrderFlowType::AlgoSlice,
        timestamp: seed,
    }
}

fn bench_router_latency(c: &mut Criterion) {
    let mut group = c.benchmark_group("router_latency");
    group.measurement_time(Duration::from_secs(5));
    group.warm_up_time(Duration::from_secs(2));

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(BenchmarkId::new("child_route", scenario.name), &scenario, |b, s| {
            b.iter_custom(|iters| {
                let samples = effective_iters(iters);
                let mut router = SmartOrderRouter::new();
                router.initialize();
                configure_router(&mut router, *s);

                let mut rng = Lcg::new(99);
                let mut hist = Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                    .expect("histogram bounds should be valid");
                let wall_start = Instant::now();

                for i in 0..samples {
                    let request = make_request(i + 1, *s, &mut rng);
                    let route_start = Instant::now();
                    let decision = router.route_order(black_box(&request));
                    let route_elapsed = route_start.elapsed();
                    let _ = hist.record(route_elapsed.as_nanos() as u64);
                    black_box(decision.action);
                }

                let wall_elapsed = wall_start.elapsed();
                write_report(*s, &hist, samples, wall_elapsed);
                wall_elapsed
            });
        });
    }

    group.finish();
}

criterion_group!(benches, bench_router_latency);
criterion_main!(benches);

