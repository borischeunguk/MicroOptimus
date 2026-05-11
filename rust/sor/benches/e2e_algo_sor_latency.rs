use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use common::cluster::{
    start_embedded_driver, AeronClusterPublisher, AeronClusterSubscriber, ClusterPublisher,
    ClusterSubscriber, AERON_DIR_ENV, STREAM_ALGO_SLICE, STREAM_PARENT_CMD, STREAM_SOR_ROUTE,
};
use common::sbe::{FixedCodec, ParentOrderCommand};
use common::shm::SharedRegion;
use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

/// Each iteration sends one parent command through the full pipeline and collects one route.
/// Orders use num_buckets=1 / participation_rate=1.0 so the engine produces exactly one
/// slice per order and completes it immediately — no active-order accumulation across iters.
const MIN_REPORT_SAMPLES: u64 = 10_000;
const ORDER_END_TIME: u64 = 1_000_000;
const PROCESS_TIME: u64 = ORDER_END_TIME - 1;

static AERON_STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static STREAM_COUNTER: AtomicI32 = AtomicI32::new(500);
static AERON_DIR: OnceLock<String> = OnceLock::new();

fn ensure_aeron_dir() {
    let dir = AERON_DIR.get_or_init(|| {
        std::path::PathBuf::from("/tmp")
            .join(format!("microoptimus_aeron_e2e_{}", std::process::id()))
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
    shm_capacity: usize,
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
            base_price: 1_500,
            shm_capacity: 64 << 20,
        },
        Scenario {
            name: "e2e_s2_open_burst",
            base_price: 1_505,
            shm_capacity: 64 << 20,
        },
        Scenario {
            name: "e2e_s3_thin_liquidity",
            base_price: 1_495,
            shm_capacity: 64 << 20,
        },
        Scenario {
            name: "e2e_s4_large_parent",
            base_price: 1_510,
            shm_capacity: 64 << 20,
        },
    ]
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!("rust_aeron_e2e_algo_sor_latency_{scenario}.json"))
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

fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("workspace root")
        .to_path_buf()
}

fn service_binary_path(workspace_root: &Path, name: &str) -> PathBuf {
    workspace_root.join("target").join("release").join(name)
}

fn ensure_service_binaries(workspace_root: &Path) {
    static BUILT: OnceLock<()> = OnceLock::new();
    BUILT.get_or_init(|| {
        let status = Command::new("cargo")
            .current_dir(workspace_root)
            .args([
                "build",
                "--release",
                "--features",
                "aeron-integration",
                "-p",
                "algo",
                "--bin",
                "algo_aeron_service",
                "-p",
                "sor",
                "--bin",
                "sor_aeron_service",
            ])
            .status()
            .expect("failed to build service binaries");
        assert!(status.success(), "cargo build for service binaries failed");
    });
}

fn stop_child(name: &str, child: &mut Child) {
    match child.try_wait() {
        Ok(Some(status)) => {
            assert!(status.success(), "{name} exited with status {status}");
            return;
        }
        Ok(None) => {}
        Err(err) => panic!("failed checking {name} process status: {err}"),
    }
    let _ = child.kill();
    for _ in 0..2_000 {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => thread::sleep(Duration::from_millis(1)),
            Err(err) => panic!("failed waiting on {name}: {err}"),
        }
    }
    panic!("timed out waiting for {name} process to stop");
}

fn assert_running(name: &str, child: &mut Child) {
    match child.try_wait() {
        Ok(Some(status)) => panic!("{name} service exited unexpectedly with status {status}"),
        Ok(None) => {}
        Err(err) => panic!("failed checking {name} process status: {err}"),
    }
}

fn spawn_algo_process(
    workspace_root: &Path,
    parent_cmd_stream: i32,
    algo_slice_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
    base_price: u64,
) -> Child {
    Command::new(service_binary_path(workspace_root, "algo_aeron_service"))
        .env(AERON_DIR_ENV, std::env::var(AERON_DIR_ENV).expect("MO_AERON_DIR not set"))
        .env("MO_PARENT_CMD_STREAM", parent_cmd_stream.to_string())
        .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
        .env("MO_SHM_PATH", shm_path)
        .env("MO_SHM_REGION_ID", shm_region_id.to_string())
        .env("MO_SHM_CAPACITY", shm_capacity.to_string())
        .env("MO_CURRENT_TIME", PROCESS_TIME.to_string())
        .env("MO_CURRENT_PRICE", base_price.to_string())
        .spawn()
        .expect("failed to spawn algo service process")
}

fn spawn_sor_process(
    workspace_root: &Path,
    algo_slice_stream: i32,
    sor_route_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
) -> Child {
    Command::new(service_binary_path(workspace_root, "sor_aeron_service"))
        .env(AERON_DIR_ENV, std::env::var(AERON_DIR_ENV).expect("MO_AERON_DIR not set"))
        .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
        .env("MO_SOR_ROUTE_STREAM", sor_route_stream.to_string())
        .env("MO_SHM_PATH", shm_path)
        .env("MO_SHM_REGION_ID", shm_region_id.to_string())
        .env("MO_SHM_CAPACITY", shm_capacity.to_string())
        .spawn()
        .expect("failed to spawn sor service process")
}

fn bench_e2e_latency(c: &mut Criterion) {
    ensure_driver();
    let ws_root = workspace_root();
    ensure_service_binaries(&ws_root);

    let mut group = c.benchmark_group("e2e_algo_sor_latency");
    group.measurement_time(configured_secs("MO_BENCH_MEASUREMENT_SECS", 5));
    group.warm_up_time(configured_secs("MO_BENCH_WARMUP_SECS", 2));
    group.sample_size(configured_sample_size());

    for scenario in scenarios() {
        group.throughput(Throughput::Elements(1));
        group.bench_with_input(
            BenchmarkId::new("parent_to_routed_children", scenario.name),
            &scenario,
            |b, s| {
                b.iter_custom(|iters| {
                    let samples = configured_samples(iters);

                    let base = next_stream_base(3);
                    let parent_cmd_stream = STREAM_PARENT_CMD + base;
                    let algo_slice_stream = STREAM_ALGO_SLICE + base;
                    let sor_route_stream = STREAM_SOR_ROUTE + base;

                    let shm_region_id = 1u32;
                    let shm_path = std::env::temp_dir().join(format!(
                        "microoptimus_rust_aeron_shm_{}_{}_{}.dat",
                        std::process::id(),
                        base,
                        s.name
                    ));
                    let shm_path_string = shm_path
                        .to_str()
                        .expect("tmp shm path must be valid UTF-8")
                        .to_owned();

                    let region = SharedRegion::create(&shm_path_string, shm_region_id, s.shm_capacity);

                    let mut algo = spawn_algo_process(
                        &ws_root,
                        parent_cmd_stream,
                        algo_slice_stream,
                        &shm_path_string,
                        shm_region_id,
                        s.shm_capacity,
                        s.base_price,
                    );
                    let mut sor = spawn_sor_process(
                        &ws_root,
                        algo_slice_stream,
                        sor_route_stream,
                        &shm_path_string,
                        shm_region_id,
                        s.shm_capacity,
                    );

                    // Give child processes a brief startup window and fail fast if they crashed.
                    thread::sleep(Duration::from_millis(200));
                    if let Ok(Some(status)) = algo.try_wait() {
                        panic!("algo service exited early with status {status}");
                    }
                    if let Ok(Some(status)) = sor.try_wait() {
                        panic!("sor service exited early with status {status}");
                    }

                    // Allow Aeron publications/subscriptions across processes to establish images.
                    thread::sleep(Duration::from_millis(200));

                    let mut cmd_pub =
                        AeronClusterPublisher::new("aeron:ipc", parent_cmd_stream)
                            .expect("cmd publisher");
                    let mut route_sub =
                        AeronClusterSubscriber::new("aeron:ipc", sor_route_stream)
                            .expect("route subscriber");

                    let mut parent_hist =
                        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                            .expect("parent histogram");
                    let mut child_hist =
                        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3)
                            .expect("child histogram");
                    let wall_start = Instant::now();
                    let mut total_children = 0u64;

                    for i in 0..samples {
                        let cmd = ParentOrderCommand {
                            sequence_id: i + 1,
                            parent_order_id: i + 1,
                            client_id: 1,
                            symbol_index: 0,
                            side: 0,
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

                        let t = Instant::now();
                        while !cmd_pub.publish(&cmd.encode()) {
                            assert_running("algo", &mut algo);
                            assert_running("sor", &mut sor);
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out publishing parent command to Aeron");
                            }
                            std::hint::spin_loop();
                        }

                        let _route_bytes = loop {
                            if let Some(b) = route_sub.poll() {
                                break b;
                            }
                            assert_running("algo", &mut algo);
                            assert_running("sor", &mut sor);
                            if t.elapsed() > hop_timeout() {
                                panic!("timed out waiting for SOR route event from Aeron");
                            }
                            std::hint::spin_loop();
                        };

                        let elapsed = t.elapsed().as_nanos() as u64;
                        let _ = parent_hist.record(elapsed);
                        let _ = child_hist.record(elapsed);
                        total_children += 1;
                    }

                    let wall = wall_start.elapsed();
                    write_report(*s, &parent_hist, &child_hist, samples, total_children, wall);

                    stop_child("algo", &mut algo);
                    stop_child("sor", &mut sor);

                    let _ = fs::remove_file(&shm_path);
                    std::mem::drop(region);
                    wall
                });
            },
        );
    }

    group.finish();
}

criterion_group!(benches, bench_e2e_latency);
criterion_main!(benches);
