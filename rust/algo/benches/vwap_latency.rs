use std::fs;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::types::{AlgorithmType, Side};
use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

use algo::algo_order::{AlgoOrder, VwapParams};
use algo::vwap::VwapAlgorithm;

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
    throughput_parent_per_sec: f64,
    throughput_child_per_sec: f64,
}

fn scenarios() -> [Scenario; 4] {
    [
        Scenario {
            name: "algo_s1_steady",
            parent_qty: 40_000,
            start_ns: 0,
            end_ns: 8_000_000,
            tick_step_ns: 50_000,
            base_price: 15_000_000,
            max_slice_size: 4_000,
            participation_rate: 0.12,
        },
        Scenario {
            name: "algo_s2_open_burst",
            parent_qty: 60_000,
            start_ns: 0,
            end_ns: 6_000_000,
            tick_step_ns: 20_000,
            base_price: 15_010_000,
            max_slice_size: 8_000,
            participation_rate: 0.18,
        },
        Scenario {
            name: "algo_s3_thin_liquidity",
            parent_qty: 30_000,
            start_ns: 0,
            end_ns: 10_000_000,
            tick_step_ns: 80_000,
            base_price: 14_990_000,
            max_slice_size: 2_000,
            participation_rate: 0.08,
        },
        Scenario {
            name: "algo_s4_large_parent",
            parent_qty: 250_000,
            start_ns: 0,
            end_ns: 20_000_000,
            tick_step_ns: 40_000,
            base_price: 15_020_000,
            max_slice_size: 12_000,
            participation_rate: 0.15,
        },
    ]
}

fn run_parent_order(scenario: Scenario, seed: u64) -> (Duration, u64) {
    let started = Instant::now();

    let mut rng = Lcg::new(seed);
    let mut order = AlgoOrder::default();
    order.init(
        seed,
        7000 + seed,
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

    let mut algo = VwapAlgorithm::new();
    algo.initialize(&order);

    let mut now = scenario.start_ns;
    let mut generated_slices = 0u64;

    while now <= scenario.end_ns && order.leaves_quantity > 0 {
        let price_jitter = rng.bounded(1000) as i64 - 500;
        let current_price = (scenario.base_price as i64 + price_jitter).max(1) as u64;

        if let Some(slice_idx) = algo.generate_slice(&mut order, now, current_price) {
            let qty = algo.get_slice(slice_idx).quantity;
            if qty > 0 {
                generated_slices += 1;
                order.on_slice_fill(qty, current_price, now);
                algo.on_slice_execution(qty);
            }
        }

        now = now.saturating_add(scenario.tick_step_ns);
    }

    if order.leaves_quantity > 0 {
        order.on_slice_fill(order.leaves_quantity, scenario.base_price, scenario.end_ns);
    }

    (started.elapsed(), generated_slices)
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!("vwap_latency_{scenario}.json"))
}

fn write_report(scenario: Scenario, hist: &Histogram<u64>, samples: u64, children: u64, elapsed: Duration) {
    let report = BenchReport {
        bench: "algo_vwap_latency",
        scenario: scenario.name,
        samples,
        latency_ns_p90: hist.value_at_quantile(0.90),
        latency_ns_p99: hist.value_at_quantile(0.99),
        latency_ns_p999: hist.value_at_quantile(0.999),
        throughput_parent_per_sec: samples as f64 / elapsed.as_secs_f64(),
        throughput_child_per_sec: children as f64 / elapsed.as_secs_f64(),
    };

    let path = report_path(scenario.name);
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    if let Ok(json) = serde_json::to_string_pretty(&report) {
        if let Err(err) = fs::write(path, json) {
            eprintln!("failed to write algo benchmark report: {err}");
        }
    }
}

fn effective_iters(iters: u64) -> u64 {
    iters.max(MIN_REPORT_SAMPLES)
}

fn bench_vwap_latency(c: &mut Criterion) {
    let mut group = c.benchmark_group("vwap_latency");
    group.measurement_time(Duration::from_secs(5));
    group.warm_up_time(Duration::from_secs(2));

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(BenchmarkId::new("parent_to_child", scenario.name), &scenario, |b, s| {
            b.iter_custom(|iters| {
                let samples = effective_iters(iters);
                let mut hist = Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                    .expect("histogram bounds should be valid");
                let wall_start = Instant::now();
                let mut total_children = 0u64;

                for i in 0..samples {
                    let (elapsed, children) = run_parent_order(*s, i + 1);
                    let _ = hist.record(elapsed.as_nanos() as u64);
                    total_children += children;
                    black_box(children);
                }

                let wall_elapsed = wall_start.elapsed();
                write_report(*s, &hist, samples, total_children, wall_elapsed);
                wall_elapsed
            });
        });
    }

    group.finish();
}

criterion_group!(benches, bench_vwap_latency);
criterion_main!(benches);

