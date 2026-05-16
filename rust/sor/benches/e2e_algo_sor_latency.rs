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

/// Parameters mirror the Java MVP benchmark (E2EAlgoSorLatencyJmh):
///   totalQuantity=40_000, participationRate=0.12, numBuckets=12,
///   maxSliceSize=4_500, tickStepNs=40_000, endNs=8_000_000
/// expectedChildren(40_000, 0.12, 12, 4_500, 0, 8_000_000, 40_000) = 100
const MIN_REPORT_SAMPLES: u64 = 10_000;
const ORDER_END_TIME: u64 = 8_000_000; // matches Java endNs
const PROCESS_TIME: u64 = ORDER_END_TIME - 1; // 7_999_999 — all buckets visible
/// Number of child slices the VWAP engine emits per parent (= Java expectedChildrenPerParent).
const EXPECTED_CHILDREN_PER_PARENT: u64 = 100;
/// Warmup parent orders sent before Criterion measurement begins (processes + Aeron warm-up).
const WARMUP_PARENTS: u64 = 200;

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
    /// VWAP slices emitted per parent order (mirrors Java expectedChildrenPerParent).
    children_per_parent: u64,
    /// total_children = samples * children_per_parent.
    total_children: u64,
    parent_latency_ns_p90: u64,
    parent_latency_ns_p99: u64,
    parent_latency_ns_p999: u64,
    /// child_latency = time from coordinator starting to wait → route event received
    /// (includes Aeron queue wait + SOR hop; matches Java child measurement).
    child_latency_ns_p90: u64,
    child_latency_ns_p99: u64,
    child_latency_ns_p999: u64,
    throughput_parent_per_sec: f64,
    throughput_child_per_sec: f64,
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
    children_per_parent: u64,
    elapsed: Duration,
) {
    let total_children = samples * children_per_parent;
    let report = E2eBenchReport {
        bench: "e2e_algo_sor_latency",
        scenario: scenario.name,
        samples,
        children_per_parent,
        total_children,
        parent_latency_ns_p90: parent_hist.value_at_quantile(0.90),
        parent_latency_ns_p99: parent_hist.value_at_quantile(0.99),
        parent_latency_ns_p999: parent_hist.value_at_quantile(0.999),
        child_latency_ns_p90: child_hist.value_at_quantile(0.90),
        child_latency_ns_p99: child_hist.value_at_quantile(0.99),
        child_latency_ns_p999: child_hist.value_at_quantile(0.999),
        throughput_parent_per_sec: samples as f64 / elapsed.as_secs_f64(),
        throughput_child_per_sec: total_children as f64 / elapsed.as_secs_f64(),
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

/// Publish one parent command and collect exactly `EXPECTED_CHILDREN_PER_PARENT` route events,
/// optionally recording latencies into histograms (pass `None` for the warmup phase).
///
/// Child latency mirrors Java: timer starts **before** polling for the route event so the
/// measurement includes Aeron queue wait + SOR processing, matching Java's `childStart =
/// System.nanoTime()` → `pollBlocking()` pattern.
#[allow(clippy::too_many_arguments)]
fn run_one_parent(
    seq: u64,
    base_price: u64,
    cmd_pub: &mut AeronClusterPublisher,
    route_sub: &mut AeronClusterSubscriber,
    algo: &mut std::process::Child,
    sor: &mut std::process::Child,
    parent_hist: Option<&mut Histogram<u64>>,
    mut child_hist: Option<&mut Histogram<u64>>,
) {
    let cmd = ParentOrderCommand {
        sequence_id: seq,
        parent_order_id: seq,
        client_id: 1,
        symbol_index: 0,
        side: 0,
        // Matches Java: totalQuantity=40_000, numBuckets=12, rate=0.12, tickStep=40_000
        // → expectedChildren(40_000,0.12,12,4_500,0,8_000_000,40_000) = 100
        total_quantity: 40_000,
        limit_price: base_price,
        start_time: 0,
        end_time: ORDER_END_TIME,
        timestamp: 0,
        num_buckets: 12,
        participation_rate: 0.12,
        min_slice_size: 100,
        max_slice_size: 4_500,
        slice_interval_ns: 0,
        ..ParentOrderCommand::default()
    };

    let parent_start = Instant::now();
    while !cmd_pub.publish(&cmd.encode()) {
        assert_running("algo", algo);
        assert_running("sor", sor);
        if parent_start.elapsed() > hop_timeout() {
            panic!("timed out publishing parent command to Aeron");
        }
        std::hint::spin_loop();
    }

    // Collect all EXPECTED_CHILDREN_PER_PARENT route events for this parent.
    // child_latency timer starts before polling (matches Java coordinator timing).
    for _ in 0..EXPECTED_CHILDREN_PER_PARENT {
        let child_start = Instant::now();
        loop {
            if route_sub.poll().is_some() {
                break;
            }
            assert_running("algo", algo);
            assert_running("sor", sor);
            if parent_start.elapsed() > hop_timeout() {
                panic!("timed out waiting for SOR route event from Aeron");
            }
            std::hint::spin_loop();
        }
        if let Some(h) = child_hist.as_deref_mut() {
            let _ = h.record(child_start.elapsed().as_nanos() as u64);
        }
    }

    if let Some(h) = parent_hist {
        let _ = h.record(parent_start.elapsed().as_nanos() as u64);
    }
}

fn bench_e2e_latency(c: &mut Criterion) {
    ensure_driver();
    let ws_root = workspace_root();
    ensure_service_binaries(&ws_root);

    let mut group = c.benchmark_group("e2e_algo_sor_latency");
    group.measurement_time(configured_secs("MO_BENCH_MEASUREMENT_SECS", 60));
    // We run WARMUP_PARENTS orders before bench_with_input, so Criterion warmup is redundant.
    // Clamp to 1 s minimum because Criterion asserts warm_up_time > 0.
    let wu_secs = std::env::var("MO_BENCH_WARMUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(1)
        .max(1);
    group.warm_up_time(Duration::from_secs(wu_secs));
    group.sample_size(configured_sample_size());

    for s in scenarios() {
        group.throughput(Throughput::Elements(1));

        // ── Process lifecycle: spawned ONCE per scenario (mirrors Java @Setup(Level.Trial)) ──
        let base = next_stream_base(3);
        let parent_cmd_stream = STREAM_PARENT_CMD + base;
        let algo_slice_stream = STREAM_ALGO_SLICE + base;
        let sor_route_stream = STREAM_SOR_ROUTE + base;

        let shm_region_id = 1u32;
        let shm_path = std::env::temp_dir().join(format!(
            "microoptimus_rust_aeron_shm_{}_{}_{}.dat",
            std::process::id(),
            base,
            s.name,
        ));
        let shm_path_string = shm_path
            .to_str()
            .expect("tmp shm path must be valid UTF-8")
            .to_owned();

        let _shm_region = SharedRegion::create(&shm_path_string, shm_region_id, s.shm_capacity);

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

        // Allow process startup and Aeron image establishment.
        thread::sleep(Duration::from_millis(200));
        if let Ok(Some(status)) = algo.try_wait() {
            panic!("algo service exited early with status {status}");
        }
        if let Ok(Some(status)) = sor.try_wait() {
            panic!("sor service exited early with status {status}");
        }
        thread::sleep(Duration::from_millis(200));

        // Coordinator only needs cmd_pub (→ algo) and route_sub (← SOR).
        // The algo→sor slice stream is internal; benchmark does not subscribe to it,
        // matching the Java coordinator which only uses coordToAlgoPub + sorToCoordSub.
        let mut cmd_pub =
            AeronClusterPublisher::new("aeron:ipc", parent_cmd_stream).expect("cmd publisher");
        let mut route_sub =
            AeronClusterSubscriber::new("aeron:ipc", sor_route_stream).expect("route subscriber");

        // ── Warmup: exercise full pipeline before measurement (matches Java implicit JIT warmup) ──
        let mut warmup_seq = 0u64;
        for _ in 0..WARMUP_PARENTS {
            warmup_seq += 1;
            run_one_parent(
                warmup_seq,
                s.base_price,
                &mut cmd_pub,
                &mut route_sub,
                &mut algo,
                &mut sor,
                None,
                None,
            );
        }

        // Histograms live outside iter_custom and are reset at the start of each Criterion
        // iteration so that warmup runs and multiple measurement iterations don't pollute each
        // other.  The final write_report overwrites perf-reports/ with the last clean sample.
        let mut parent_hist =
            Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3).expect("parent histogram");
        let mut child_hist =
            Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3).expect("child histogram");

        // seq counter shared across all iter_custom calls — just needs to be monotone.
        let mut seq = warmup_seq;

        group.bench_with_input(
            BenchmarkId::new("parent_to_routed_children", s.name),
            &s,
            |b, sc| {
                b.iter_custom(|iters| {
                    let samples = configured_samples(iters);
                    parent_hist.reset();
                    child_hist.reset();

                    let wall_start = Instant::now();
                    for _ in 0..samples {
                        seq += 1;
                        run_one_parent(
                            seq,
                            sc.base_price,
                            &mut cmd_pub,
                            &mut route_sub,
                            &mut algo,
                            &mut sor,
                            Some(&mut parent_hist),
                            Some(&mut child_hist),
                        );
                    }
                    let wall = wall_start.elapsed();
                    write_report(*sc, &parent_hist, &child_hist, samples, EXPECTED_CHILDREN_PER_PARENT, wall);
                    // Scale wall time to match Criterion's expected iters count so that
                    // Criterion displays the correct per-parent latency and doesn't crash
                    // when plotting near-zero values.  Our real measurements are in
                    // write_report; Criterion's display is ancillary.
                    let nanos = wall.as_secs_f64() * 1e9 * iters as f64 / samples.max(1) as f64;
                    Duration::from_nanos(nanos.min(u64::MAX as f64) as u64).max(Duration::from_nanos(1))
                });
            },
        );

        // ── Teardown: kill processes after all Criterion iterations complete ──
        stop_child("algo", &mut algo);
        stop_child("sor", &mut sor);
        let _ = fs::remove_file(&shm_path);
        std::mem::drop(_shm_region);
    }

    group.finish();
}

criterion_group!(benches, bench_e2e_latency);
criterion_main!(benches);
