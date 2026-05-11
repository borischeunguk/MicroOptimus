use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use common::cluster::{
    start_embedded_driver, AeronClusterPublisher, AeronClusterSubscriber, ClusterPublisher,
    ClusterSubscriber, AERON_DIR_ENV, STREAM_ALGO_SLICE, STREAM_SOR_ROUTE,
};
use common::messages::SliceMsg;
use common::sbe::{AlgoSliceRefEvent, FixedCodec};
use common::shm::SharedRegion;
use common::types::{Side, VenueId};
use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

use sor::cluster_service::SorClusterService;
use sor::router::SmartOrderRouter;
use sor::venue::VenueConfig;

const MIN_REPORT_SAMPLES: u64 = 10_000;

static AERON_STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static STREAM_COUNTER: AtomicI32 = AtomicI32::new(200);
static AERON_DIR: OnceLock<String> = OnceLock::new();

fn ensure_aeron_dir() {
    let dir = AERON_DIR.get_or_init(|| {
        std::path::PathBuf::from("/tmp")
            .join(format!("microoptimus_aeron_sor_{}", std::process::id()))
            .to_string_lossy()
            .into_owned()
    });
    std::env::set_var(AERON_DIR_ENV, dir);
}

fn ensure_driver() {
    ensure_aeron_dir();
    AERON_STOP.get_or_init(|| {
        let (stop, handle) = start_embedded_driver();
        std::mem::forget(handle);
        stop
    });
}

fn next_stream_base(count: i32) -> i32 {
    STREAM_COUNTER.fetch_add(count, Ordering::Relaxed)
}

fn configured_samples(iters: u64) -> u64 {
    std::env::var("MO_BENCH_SAMPLES")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or_else(|| iters.max(MIN_REPORT_SAMPLES))
}

fn configured_secs(key: &str, default_secs: u64) -> Duration {
    let secs = std::env::var(key)
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(default_secs);
    Duration::from_secs(secs)
}

fn configured_sample_size() -> usize {
    std::env::var("MO_BENCH_CRITERION_SAMPLE_SIZE")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.max(10))
        .unwrap_or(10)
}

fn hop_timeout() -> Duration {
    configured_secs("MO_BENCH_HOP_TIMEOUT_SECS", 30)
}

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
        .join(format!("rust_aeron_router_latency_{scenario}.json"))
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

fn configure_router(router: &mut SmartOrderRouter, s: Scenario) {
    router.configure_venue(VenueConfig::new(
        VenueId::Cme,
        90,
        true,
        1_500_000,
        s.cme_latency_ns,
        s.cme_fill_rate,
        0.00010,
    ));
    router.configure_venue(VenueConfig::new(
        VenueId::Nasdaq,
        85,
        true,
        1_200_000,
        s.nasdaq_latency_ns,
        s.nasdaq_fill_rate,
        0.00015,
    ));
    router.set_internal_liquidity_threshold(100);
}

fn bench_router_latency(c: &mut Criterion) {
    ensure_driver();

    let mut group = c.benchmark_group("router_latency");
    group.measurement_time(configured_secs("MO_BENCH_MEASUREMENT_SECS", 5));
    group.warm_up_time(configured_secs("MO_BENCH_WARMUP_SECS", 2));
    group.sample_size(configured_sample_size());

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(
            BenchmarkId::new("child_route", scenario.name),
            &scenario,
            |b, s| {
                b.iter_custom(|iters| {
                    let samples = configured_samples(iters);

                    // Fresh stream IDs per iter_custom call to avoid cross-call message leakage.
                    let base = next_stream_base(2);
                    let algo_slice_stream = STREAM_ALGO_SLICE + base;
                    let sor_route_stream = STREAM_SOR_ROUTE + base;

                    // Set up connections outside the timed loop.
                    let mut slice_pub =
                        AeronClusterPublisher::new("aeron:ipc", algo_slice_stream)
                            .expect("slice publisher");
                    let slice_sub =
                        AeronClusterSubscriber::new("aeron:ipc", algo_slice_stream)
                            .expect("SOR slice subscriber");
                    let route_pub =
                        AeronClusterPublisher::new("aeron:ipc", sor_route_stream)
                            .expect("SOR route publisher");
                    let mut route_sub =
                        AeronClusterSubscriber::new("aeron:ipc", sor_route_stream)
                            .expect("route subscriber");

                    let mut router = SmartOrderRouter::new();
                    router.initialize();
                    configure_router(&mut router, *s);
                    let mut sor = SorClusterService::new_with_router(slice_sub, route_pub, router);
                    let mut region = SharedRegion::new_anon(1, 32 << 20);

                    // Give Aeron endpoints time to establish images before sending timed events.
                    std::thread::sleep(Duration::from_millis(200));

                    let mut rng = Lcg::new(77);
                    let mut hist =
                        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                            .expect("histogram");
                    let wall_start = Instant::now();

                    for i in 0..samples {
                        let qty = s.min_qty + rng.bounded(s.qty_span).max(1);
                        let side =
                            if rng.bounded(2) == 0 { Side::Buy } else { Side::Sell };

                        // Write slice payload to shared region (outside timing).
                        let slice = SliceMsg {
                            slice_id: i + 1,
                            parent_order_id: 5000 + i,
                            symbol_index: 0,
                            side,
                            quantity: qty,
                            price: s.price,
                            slice_number: 1,
                            timestamp: i,
                            ..SliceMsg::default()
                        };
                        let slice_bytes = unsafe {
                            std::slice::from_raw_parts(
                                &slice as *const SliceMsg as *const u8,
                                std::mem::size_of::<SliceMsg>(),
                            )
                        };
                        let shm_ref =
                            region.write(2, slice_bytes).expect("region write");
                        let event = AlgoSliceRefEvent {
                            sequence_id: i + 1,
                            parent_order_id: 5000 + i,
                            slice_id: i + 1,
                            timestamp: i,
                            shm_ref,
                        };

                        // Timed: Aeron publish → SOR process → Aeron deliver → receive.
                        let t = Instant::now();
                        while !slice_pub.publish(&event.encode()) {
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out publishing slice event to Aeron");
                            }
                            std::hint::spin_loop();
                        }
                        loop {
                            if sor.poll(&mut region) > 0 {
                                break;
                            }
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out waiting for SOR to route slice");
                            }
                            std::hint::spin_loop();
                        }
                        loop {
                            if route_sub.poll().is_some() {
                                break;
                            }
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out waiting for route event from Aeron");
                            }
                            std::hint::spin_loop();
                        }
                        let _ = hist.record(t.elapsed().as_nanos() as u64);
                    }

                    let wall = wall_start.elapsed();
                    write_report(*s, &hist, samples, wall);
                    wall
                });
            },
        );
    }

    group.finish();
}

criterion_group!(benches, bench_router_latency);
criterion_main!(benches);
