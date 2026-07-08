/// End-to-end benchmark: Algo -> SOR (market-data-aware) over Chronicle queues.
///
/// Pipeline per iteration:
///   1. Coordinator publishes one `MarketDataUpdate` tick on `STREAM_MARKET_DATA`.
///   2. `marketdata_chronicle_service` ingests the tick and updates `MarketDataRegion`.
///   3. Coordinator publishes one `ParentOrderCommand` on `STREAM_PARENT_CMD`.
///   4. `algo_chronicle_service` emits child slices on `STREAM_ALGO_SLICE`.
///   5. `sor_marketdata_chronicle_service` routes each slice using fresh MD snapshot.
///   6. Coordinator collects all route events from `STREAM_SOR_ROUTE`.
///
/// Output: `perf-reports/rust_chronicle_e2e_algo_sor_marketdata_latency_e2e_s1_steady.json`

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant};

use common::chronicle_cluster::{
    ChronicleClusterConfig, ChronicleClusterPublisher, ChronicleClusterSubscriber, CHRONICLE_DIR_ENV,
};
use common::cluster::{
    ClusterPublisher, ClusterSubscriber, STREAM_ALGO_SLICE, STREAM_MARKET_DATA, STREAM_PARENT_CMD,
    STREAM_SOR_ROUTE,
};
use common::sbe::{FixedCodec, MarketDataUpdate, ParentOrderCommand};
use common::shm::{MarketDataRegion, SharedRegion, MD_MAX_SYMBOLS};
use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use hdrhistogram::Histogram;
use serde::Serialize;

const MIN_REPORT_SAMPLES: u64 = 100;
const ORDER_END_TIME: u64 = 8_000_000;
const PROCESS_TIME: u64 = ORDER_END_TIME - 1;
const EXPECTED_CHILDREN_PER_PARENT: u64 = 100;
const EMPTY_POLL_SPIN_BUDGET: u32 = 128;
const WARMUP_PARENTS: u64 = 200;
const DEFAULT_QUEUE_CAPACITY: usize = 64 << 20;

static CHRONICLE_DIR: OnceLock<String> = OnceLock::new();

fn ensure_chronicle_dir() -> String {
    CHRONICLE_DIR
        .get_or_init(|| {
            std::env::temp_dir()
                .join(format!("microoptimus_chronicle_md_e2e_{}", std::process::id()))
                .to_string_lossy()
                .into_owned()
        })
        .clone()
}

fn configured_samples(iters: u64) -> u64 {
    std::env::var("MO_BENCH_CHRONICLE_MD_SAMPLES")
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
    std::env::var("MO_BENCH_CHRONICLE_MD_CRITERION_SAMPLE_SIZE")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.max(10))
        .unwrap_or(10)
}

fn hop_timeout() -> Duration {
    configured_secs("MO_BENCH_CHRONICLE_MD_HOP_TIMEOUT_SECS", 30)
}

#[derive(Serialize)]
struct E2eMdBenchReport {
    bench: &'static str,
    scenario: String,
    samples: u64,
    children_per_parent: u64,
    total_children: u64,
    parent_latency_ns_p90: u64,
    parent_latency_ns_p99: u64,
    parent_latency_ns_p999: u64,
    child_latency_ns_p90: u64,
    child_latency_ns_p99: u64,
    child_latency_ns_p999: u64,
    throughput_parent_per_sec: f64,
    throughput_child_per_sec: f64,
}

fn report_path(scenario: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("perf-reports")
        .join(format!(
            "rust_chronicle_e2e_algo_sor_marketdata_latency_{scenario}.json"
        ))
}

fn write_report(
    scenario: &str,
    parent_hist: &Histogram<u64>,
    child_hist: &Histogram<u64>,
    samples: u64,
    elapsed: Duration,
) {
    let total_children = samples * EXPECTED_CHILDREN_PER_PARENT;
    let report = E2eMdBenchReport {
        bench: "e2e_algo_sor_chronicle_marketdata_latency",
        scenario: scenario.to_owned(),
        samples,
        children_per_parent: EXPECTED_CHILDREN_PER_PARENT,
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

    let path = report_path(scenario);
    if let Some(parent_dir) = path.parent() {
        let _ = fs::create_dir_all(parent_dir);
    }
    if let Ok(json) = serde_json::to_string_pretty(&report) {
        if let Err(err) = fs::write(&path, json) {
            eprintln!("failed to write bench report: {err}");
        } else {
            eprintln!("bench report written to {}", path.display());
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
                "chronicle-integration",
                "-p",
                "algo",
                "--bin",
                "algo_chronicle_service",
                "-p",
                "marketdata",
                "--bin",
                "marketdata_chronicle_service",
                "-p",
                "sor",
                "--bin",
                "sor_marketdata_chronicle_service",
            ])
            .status()
            .expect("failed to build service binaries");
        assert!(
            status.success(),
            "cargo build for chronicle service binaries failed"
        );
    });
}

fn precreate_stream_queues(base_dir: &str, queue_capacity: usize) {
    for stream in [
        STREAM_PARENT_CMD,
        STREAM_ALGO_SLICE,
        STREAM_SOR_ROUTE,
        STREAM_MARKET_DATA,
    ] {
        let cfg = ChronicleClusterConfig::new(base_dir, stream, queue_capacity);
        // Create/truncate is intentional at benchmark setup for clean queue state.
        let _ = ChronicleClusterPublisher::create(&cfg)
            .unwrap_or_else(|e| panic!("failed creating queue for stream {stream}: {e}"));
    }
}

fn stop_child(name: &str, child: &mut Child) {
    match child.try_wait() {
        Ok(Some(status)) => {
            assert!(status.success(), "{name} exited with status {status}");
            return;
        }
        Ok(None) => {}
        Err(err) => panic!("failed checking {name} status: {err}"),
    }
    let _ = child.kill();
    for _ in 0..2_000 {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => thread::sleep(Duration::from_millis(1)),
            Err(err) => panic!("failed waiting on {name}: {err}"),
        }
    }
    panic!("timed out waiting for {name} to stop");
}

fn assert_running(name: &str, child: &mut Child) {
    match child.try_wait() {
        Ok(Some(status)) => panic!("{name} service exited unexpectedly: {status}"),
        Ok(None) => {}
        Err(err) => panic!("failed checking {name}: {err}"),
    }
}

fn spawn_algo_process(
    workspace_root: &Path,
    chronicle_dir: &str,
    parent_cmd_stream: i32,
    algo_slice_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
    base_price: u64,
) -> Child {
    Command::new(service_binary_path(workspace_root, "algo_chronicle_service"))
        .env(CHRONICLE_DIR_ENV, chronicle_dir)
        .env("MO_PARENT_CMD_STREAM", parent_cmd_stream.to_string())
        .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
        .env("MO_CHRONICLE_QUEUE_CAPACITY", DEFAULT_QUEUE_CAPACITY.to_string())
        .env("MO_SHM_PATH", shm_path)
        .env("MO_SHM_REGION_ID", shm_region_id.to_string())
        .env("MO_SHM_CAPACITY", shm_capacity.to_string())
        .env("MO_CURRENT_TIME", PROCESS_TIME.to_string())
        .env("MO_CURRENT_PRICE", base_price.to_string())
        .spawn()
        .expect("failed to spawn algo service")
}

fn spawn_marketdata_process(
    workspace_root: &Path,
    chronicle_dir: &str,
    md_stream: i32,
    md_shm_path: &str,
) -> Child {
    Command::new(service_binary_path(workspace_root, "marketdata_chronicle_service"))
        .env(CHRONICLE_DIR_ENV, chronicle_dir)
        .env("MO_MARKET_DATA_STREAM", md_stream.to_string())
        .env("MO_CHRONICLE_QUEUE_CAPACITY", DEFAULT_QUEUE_CAPACITY.to_string())
        .env("MO_MD_SHM_PATH", md_shm_path)
        .env("MO_MD_SHM_CAPACITY", MD_MAX_SYMBOLS.to_string())
        // Benchmark should measure coordinator-published market-data ticks only.
        .env("MO_MD_SYNTHETIC_ENABLE", "0")
        .env("MO_MD_SYNTHETIC_SYMBOL", "0")
        .env("MO_MD_SYNTHETIC_BASE_PRICE", "1500")
        .spawn()
        .expect("failed to spawn marketdata service")
}

fn spawn_sor_md_process(
    workspace_root: &Path,
    chronicle_dir: &str,
    algo_slice_stream: i32,
    sor_route_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
    md_shm_path: &str,
) -> Child {
    Command::new(service_binary_path(
        workspace_root,
        "sor_marketdata_chronicle_service",
    ))
    .env(CHRONICLE_DIR_ENV, chronicle_dir)
    .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
    .env("MO_SOR_ROUTE_STREAM", sor_route_stream.to_string())
    .env("MO_CHRONICLE_QUEUE_CAPACITY", DEFAULT_QUEUE_CAPACITY.to_string())
    .env("MO_SHM_PATH", shm_path)
    .env("MO_SHM_REGION_ID", shm_region_id.to_string())
    .env("MO_SHM_CAPACITY", shm_capacity.to_string())
    .env("MO_MD_SHM_PATH", md_shm_path)
    .env("MO_MD_SHM_CAPACITY", MD_MAX_SYMBOLS.to_string())
    .spawn()
    .expect("failed to spawn sor-marketdata service")
}

#[inline]
fn jitter_price(seq: u64, base: u64) -> (u64, u64) {
    let mut x = seq
        .wrapping_mul(6_364_136_223_846_793_005)
        .wrapping_add(1_442_695_040_888_963_407);
    x ^= x >> 30;
    let offset = (x % 11) as i64 - 5;
    let bid = (base as i64 + offset - 1).max(1) as u64;
    let ask = (base as i64 + offset + 1).max(1) as u64;
    (bid, ask)
}

#[allow(clippy::too_many_arguments)]
fn run_one_parent_with_md(
    seq: u64,
    base_price: u64,
    md_pub: &mut ChronicleClusterPublisher,
    cmd_pub: &mut ChronicleClusterPublisher,
    route_sub: &mut ChronicleClusterSubscriber,
    algo: &mut Child,
    sor: &mut Child,
    md_svc: &mut Child,
    parent_hist: Option<&mut Histogram<u64>>,
    mut child_hist: Option<&mut Histogram<u64>>,
) {
    let (bid_price, ask_price) = jitter_price(seq, base_price);
    let tick = MarketDataUpdate {
        symbol_index: 0,
        _pad: 0,
        bid_price,
        ask_price,
        last_price: base_price,
        bid_size: 50_000,
        ask_size: 50_000,
        timestamp: seq,
    };
    let _ = md_pub.publish(&tick.encode());

    let cmd = ParentOrderCommand {
        sequence_id: seq,
        parent_order_id: seq,
        client_id: 1,
        symbol_index: 0,
        side: 0,
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
    let mut pub_spins: u32 = 0;
    while !cmd_pub.publish(&cmd.encode()) {
        assert_running("algo", algo);
        assert_running("sor", sor);
        assert_running("md_svc", md_svc);
        if parent_start.elapsed() > hop_timeout() {
            panic!("timed out publishing parent command");
        }
        pub_spins += 1;
        if pub_spins <= EMPTY_POLL_SPIN_BUDGET {
            std::hint::spin_loop();
        } else {
            thread::yield_now();
            pub_spins = 0;
        }
    }

    for _ in 0..EXPECTED_CHILDREN_PER_PARENT {
        let child_start = Instant::now();
        let mut route_spins: u32 = 0;
        loop {
            if route_sub.poll().is_some() {
                break;
            }
            assert_running("algo", algo);
            assert_running("sor", sor);
            assert_running("md_svc", md_svc);
            if parent_start.elapsed() > hop_timeout() {
                panic!("timed out waiting for SOR route event");
            }
            route_spins += 1;
            if route_spins <= EMPTY_POLL_SPIN_BUDGET {
                std::hint::spin_loop();
            } else {
                thread::yield_now();
                route_spins = 0;
            }
        }
        if let Some(h) = child_hist.as_deref_mut() {
            let _ = h.record(child_start.elapsed().as_nanos() as u64);
        }
    }

    if let Some(h) = parent_hist {
        let _ = h.record(parent_start.elapsed().as_nanos() as u64);
    }
}

fn bench_e2e_md_latency(c: &mut Criterion) {
    let ws_root = workspace_root();
    ensure_service_binaries(&ws_root);

    let chronicle_dir = ensure_chronicle_dir();
    let _ = fs::create_dir_all(&chronicle_dir);
    precreate_stream_queues(&chronicle_dir, DEFAULT_QUEUE_CAPACITY);

    let mut group = c.benchmark_group("e2e_algo_sor_chronicle_marketdata_latency");
    group.measurement_time(configured_secs(
        "MO_BENCH_CHRONICLE_MD_MEASUREMENT_SECS",
        60,
    ));
    let wu_secs = std::env::var("MO_BENCH_CHRONICLE_MD_WARMUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(1)
        .max(1);
    group.warm_up_time(Duration::from_secs(wu_secs));
    group.sample_size(configured_sample_size());

    let scenario_name = "e2e_s1_steady";
    let base_price: u64 = 1_500;
    let shm_capacity: usize = 64 << 20;
    let shm_region_id: u32 = 1;

    group.throughput(Throughput::Elements(1));

    let parent_cmd_stream = STREAM_PARENT_CMD;
    let algo_slice_stream = STREAM_ALGO_SLICE;
    let sor_route_stream = STREAM_SOR_ROUTE;
    let md_stream = STREAM_MARKET_DATA;

    let pid = std::process::id();

    let shm_path = std::env::temp_dir()
        .join(format!("mo_rust_chronicle_shm_{pid}_{scenario_name}.dat"))
        .to_string_lossy()
        .into_owned();
    let shm_region = SharedRegion::create(&shm_path, shm_region_id, shm_capacity);

    let md_shm_path = std::env::temp_dir()
        .join(format!("mo_rust_chronicle_md_{pid}_{scenario_name}.dat"))
        .to_string_lossy()
        .into_owned();
    let md_region = MarketDataRegion::create(&md_shm_path, MD_MAX_SYMBOLS);

    let mut md_svc = spawn_marketdata_process(&ws_root, &chronicle_dir, md_stream, &md_shm_path);
    let mut sor = spawn_sor_md_process(
        &ws_root,
        &chronicle_dir,
        algo_slice_stream,
        sor_route_stream,
        &shm_path,
        shm_region_id,
        shm_capacity,
        &md_shm_path,
    );
    let mut algo = spawn_algo_process(
        &ws_root,
        &chronicle_dir,
        parent_cmd_stream,
        algo_slice_stream,
        &shm_path,
        shm_region_id,
        shm_capacity,
        base_price,
    );

    thread::sleep(Duration::from_millis(400));
    for (name, child) in [
        ("algo", &mut algo),
        ("sor", &mut sor),
        ("md_svc", &mut md_svc),
    ] {
        if let Ok(Some(status)) = child.try_wait() {
            panic!("{name} service exited early: {status}");
        }
    }

    let md_cfg = ChronicleClusterConfig::new(&chronicle_dir, md_stream, DEFAULT_QUEUE_CAPACITY);
    let cmd_cfg =
        ChronicleClusterConfig::new(&chronicle_dir, parent_cmd_stream, DEFAULT_QUEUE_CAPACITY);
    let route_cfg =
        ChronicleClusterConfig::new(&chronicle_dir, sor_route_stream, DEFAULT_QUEUE_CAPACITY);

    let mut md_pub = ChronicleClusterPublisher::open(&md_cfg).expect("md publisher");
    let mut cmd_pub = ChronicleClusterPublisher::open(&cmd_cfg).expect("cmd publisher");
    let mut route_sub = ChronicleClusterSubscriber::open(&route_cfg).expect("route subscriber");

    let mut seq = 0u64;
    for _ in 0..WARMUP_PARENTS {
        seq += 1;
        run_one_parent_with_md(
            seq,
            base_price,
            &mut md_pub,
            &mut cmd_pub,
            &mut route_sub,
            &mut algo,
            &mut sor,
            &mut md_svc,
            None,
            None,
        );
    }

    let mut parent_hist =
        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3).expect("parent histogram");
    let mut child_hist =
        Histogram::<u64>::new_with_bounds(1, 60_000_000_000, 3).expect("child histogram");

    group.bench_with_input(
        BenchmarkId::new("parent_to_routed_children_with_md", scenario_name),
        &base_price,
        |b, &bp| {
            b.iter_custom(|iters| {
                let samples = configured_samples(iters);
                parent_hist.reset();
                child_hist.reset();

                let wall_start = Instant::now();
                for _ in 0..samples {
                    seq += 1;
                    run_one_parent_with_md(
                        seq,
                        bp,
                        &mut md_pub,
                        &mut cmd_pub,
                        &mut route_sub,
                        &mut algo,
                        &mut sor,
                        &mut md_svc,
                        Some(&mut parent_hist),
                        Some(&mut child_hist),
                    );
                }
                let wall = wall_start.elapsed();
                write_report(scenario_name, &parent_hist, &child_hist, samples, wall);
                let nanos = wall.as_secs_f64() * 1e9 * iters as f64 / samples.max(1) as f64;
                Duration::from_nanos(nanos.min(u64::MAX as f64) as u64).max(Duration::from_nanos(1))
            });
        },
    );

    stop_child("md_svc", &mut md_svc);
    stop_child("sor", &mut sor);
    stop_child("algo", &mut algo);

    let _ = fs::remove_file(&shm_path);
    let _ = fs::remove_file(&md_shm_path);
    for stream in [
        STREAM_PARENT_CMD,
        STREAM_ALGO_SLICE,
        STREAM_SOR_ROUTE,
        STREAM_MARKET_DATA,
    ] {
        let cfg = ChronicleClusterConfig::new(&chronicle_dir, stream, DEFAULT_QUEUE_CAPACITY);
        if let Ok(path) = cfg.queue_path() {
            let _ = fs::remove_file(path);
        }
    }
    let _ = fs::remove_dir_all(&chronicle_dir);
    std::mem::drop(shm_region);
    std::mem::drop(md_region);

    group.finish();
}

criterion_group!(benches, bench_e2e_md_latency);
criterion_main!(benches);

