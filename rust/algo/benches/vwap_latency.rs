use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use algo::cluster_service::AlgoClusterService;
use common::cluster::{
    start_embedded_driver, AeronClusterPublisher, AeronClusterSubscriber, ClusterPublisher,
    ClusterSubscriber, AERON_DIR_ENV, STREAM_ALGO_SLICE, STREAM_PARENT_CMD,
};
use common::sbe::{FixedCodec, ParentOrderCommand};
use common::shm::SharedRegion;
use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

/// Each iteration sends one parent command through AlgoClusterService and receives
/// the resulting AlgoSliceRefEvent. Orders use num_buckets=1 / participation_rate=1.0
/// so the engine produces exactly one slice per order and completes immediately.
const MIN_REPORT_SAMPLES: u64 = 10_000;
const ORDER_END_TIME: u64 = 1_000_000;
const PROCESS_TIME: u64 = ORDER_END_TIME - 1;

static AERON_STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static STREAM_COUNTER: AtomicI32 = AtomicI32::new(800);
static AERON_DIR: OnceLock<String> = OnceLock::new();

fn ensure_aeron_dir() {
    let dir = AERON_DIR.get_or_init(|| {
        std::path::PathBuf::from("/tmp")
            .join(format!("microoptimus_aeron_algo_{}", std::process::id()))
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
    base_price: u64,
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
        Scenario { name: "algo_s1_steady", base_price: 15_000_000 },
        Scenario { name: "algo_s2_open_burst", base_price: 15_010_000 },
        Scenario { name: "algo_s3_thin_liquidity", base_price: 14_990_000 },
        Scenario { name: "algo_s4_large_parent", base_price: 15_020_000 },
    ]
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!("rust_aeron_vwap_latency_{scenario}.json"))
}

fn write_report(scenario: Scenario, hist: &Histogram<u64>, samples: u64, elapsed: Duration) {
    let report = BenchReport {
        bench: "algo_vwap_latency",
        scenario: scenario.name,
        samples,
        latency_ns_p90: hist.value_at_quantile(0.90),
        latency_ns_p99: hist.value_at_quantile(0.99),
        latency_ns_p999: hist.value_at_quantile(0.999),
        throughput_parent_per_sec: samples as f64 / elapsed.as_secs_f64(),
        throughput_child_per_sec: samples as f64 / elapsed.as_secs_f64(),
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

fn bench_vwap_latency(c: &mut Criterion) {
    ensure_driver();

    let mut group = c.benchmark_group("vwap_latency");
    group.measurement_time(configured_secs("MO_BENCH_MEASUREMENT_SECS", 5));
    group.warm_up_time(configured_secs("MO_BENCH_WARMUP_SECS", 2));
    group.sample_size(configured_sample_size());

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(
            BenchmarkId::new("parent_to_child", scenario.name),
            &scenario,
            |b, s| {
                b.iter_custom(|iters| {
                    let samples = configured_samples(iters);

                    // Fresh stream pair per iter_custom call.
                    let base = next_stream_base(2);
                    let parent_cmd_stream = STREAM_PARENT_CMD + base;
                    let algo_slice_stream = STREAM_ALGO_SLICE + base;

                    // Build the algo pipeline (outside timing).
                    let mut cmd_pub =
                        AeronClusterPublisher::new("aeron:ipc", parent_cmd_stream)
                            .expect("cmd publisher");
                    let cmd_sub =
                        AeronClusterSubscriber::new("aeron:ipc", parent_cmd_stream)
                            .expect("algo cmd subscriber");
                    let slice_pub =
                        AeronClusterPublisher::new("aeron:ipc", algo_slice_stream)
                            .expect("algo slice publisher");
                    let mut slice_sub =
                        AeronClusterSubscriber::new("aeron:ipc", algo_slice_stream)
                            .expect("slice subscriber");

                    let mut algo = AlgoClusterService::new(cmd_sub, slice_pub);
                    let mut region = SharedRegion::new_anon(1, 32 << 20);

                    // Allow Aeron publication/subscription images to establish before timed sends.
                    std::thread::sleep(Duration::from_millis(200));

                    let mut hist =
                        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                            .expect("histogram");
                    let wall_start = Instant::now();

                    for i in 0..samples {
                        // One-bucket, full-participation order produces exactly one slice.
                        let cmd = ParentOrderCommand {
                            sequence_id: i + 1,
                            parent_order_id: i + 1,
                            client_id: 1,
                            symbol_index: 0,
                            side: 0, // Buy
                            total_quantity: 1_000,
                            limit_price: s.base_price,
                            start_time: 0,
                            end_time: ORDER_END_TIME,
                            timestamp: 0,
                            num_buckets: 1,
                            participation_rate: 1.0,
                            min_slice_size: 10,
                            max_slice_size: 1_000,
                            slice_interval_ns: 0,
                            ..ParentOrderCommand::default()
                        };

                        // Timed: cmd publish → Aeron IPC → algo process → slice received.
                        let t = Instant::now();
                        while !cmd_pub.publish(&cmd.encode()) {
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out publishing parent command to Aeron");
                            }
                            std::hint::spin_loop();
                        }

                        // Spin until algo service receives and processes the command.
                        loop {
                            if algo.poll(&mut region, PROCESS_TIME, s.base_price) > 0 {
                                break;
                            }
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out waiting for algo service to process command");
                            }
                            std::hint::spin_loop();
                        }

                        // Receive the slice ref event.
                        loop {
                            if slice_sub.poll().is_some() {
                                break;
                            }
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out waiting for slice event from Aeron");
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

criterion_group!(benches, bench_vwap_latency);
criterion_main!(benches);
