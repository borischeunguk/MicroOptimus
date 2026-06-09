/// End-to-end benchmark: Algo → SOR (market-data-aware) with live ticks.
///
/// Pipeline per iteration:
///   1. Coordinator publishes one `MarketDataUpdate` tick on `STREAM_MARKET_DATA`
///      with jittered bid/ask around `base_price` (models real-time price movement).
///   2. The `marketdata_aeron_service` ingests the tick into its ring buffer and
///      writes the latest snapshot to the dedicated `MarketDataRegion` (mmap file).
///   3. Coordinator publishes one `ParentOrderCommand` to `STREAM_PARENT_CMD`.
///   4. `algo_aeron_service` slices it into `EXPECTED_CHILDREN_PER_PARENT` child
///      slices on `STREAM_ALGO_SLICE`.
///   5. `sor_marketdata_aeron_service` reads a **fresh** MD snapshot per slice
///      and publishes route events on `STREAM_SOR_ROUTE`.
///   6. Coordinator collects all route events and records latencies.
///
/// Only the `e2e_s1_steady` scenario is run.
///
/// Output: `perf-reports/rust_aeron_e2e_algo_sor_marketdata_latency_e2e_s1_steady.json`

use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use common::cluster::{
    start_embedded_driver, AeronClusterPublisher, AeronClusterSubscriber, ClusterPublisher,
    ClusterSubscriber, AERON_DIR_ENV, STREAM_ALGO_SLICE, STREAM_MARKET_DATA, STREAM_PARENT_CMD,
    STREAM_SOR_ROUTE,
};
use common::sbe::{FixedCodec, MarketDataUpdate, ParentOrderCommand, SorRouteRefEvent};
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

static AERON_STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static STREAM_COUNTER: AtomicI32 = AtomicI32::new(800); // start offset distinct from other bench
static AERON_DIR: OnceLock<String> = OnceLock::new();

fn ensure_aeron_dir() {
    let dir = AERON_DIR.get_or_init(|| {
        std::path::PathBuf::from("/tmp")
            .join(format!("microoptimus_aeron_md_e2e_{}", std::process::id()))
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
    std::env::var("MO_BENCH_MD_SAMPLES")
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
    std::env::var("MO_BENCH_MD_CRITERION_SAMPLE_SIZE")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.max(10))
        .unwrap_or(10)
}

fn hop_timeout() -> Duration {
    configured_secs("MO_BENCH_MD_HOP_TIMEOUT_SECS", 30)
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
        .join(format!("rust_aeron_e2e_algo_sor_marketdata_latency_{scenario}.json"))
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
        bench: "e2e_algo_sor_marketdata_latency",
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

fn service_binary_path(workspace_root: &std::path::Path, name: &str) -> PathBuf {
    workspace_root.join("target").join("release").join(name)
}

fn ensure_service_binaries(workspace_root: &std::path::Path) {
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
                "marketdata",
                "--bin",
                "marketdata_aeron_service",
                "-p",
                "sor",
                "--bin",
                "sor_marketdata_aeron_service",
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
    workspace_root: &std::path::Path,
    parent_cmd_stream: i32,
    algo_slice_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
    base_price: u64,
) -> Child {
    Command::new(service_binary_path(workspace_root, "algo_aeron_service"))
        .env(AERON_DIR_ENV, std::env::var(AERON_DIR_ENV).expect("MO_AERON_DIR"))
        .env("MO_PARENT_CMD_STREAM", parent_cmd_stream.to_string())
        .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
        .env("MO_SHM_PATH", shm_path)
        .env("MO_SHM_REGION_ID", shm_region_id.to_string())
        .env("MO_SHM_CAPACITY", shm_capacity.to_string())
        .env("MO_CURRENT_TIME", PROCESS_TIME.to_string())
        .env("MO_CURRENT_PRICE", base_price.to_string())
        .spawn()
        .expect("failed to spawn algo service")
}

fn spawn_marketdata_process(
    workspace_root: &std::path::Path,
    md_stream: i32,
    md_shm_path: &str,
) -> Child {
    Command::new(service_binary_path(workspace_root, "marketdata_aeron_service"))
        .env(AERON_DIR_ENV, std::env::var(AERON_DIR_ENV).expect("MO_AERON_DIR"))
        .env("MO_MARKET_DATA_STREAM", md_stream.to_string())
        .env("MO_MD_SHM_PATH", md_shm_path)
        .env("MO_MD_SHM_CAPACITY", MD_MAX_SYMBOLS.to_string())
        .spawn()
        .expect("failed to spawn marketdata service")
}

fn spawn_sor_md_process(
    workspace_root: &std::path::Path,
    algo_slice_stream: i32,
    sor_route_stream: i32,
    shm_path: &str,
    shm_region_id: u32,
    shm_capacity: usize,
    md_shm_path: &str,
) -> Child {
    Command::new(service_binary_path(workspace_root, "sor_marketdata_aeron_service"))
        .env(AERON_DIR_ENV, std::env::var(AERON_DIR_ENV).expect("MO_AERON_DIR"))
        .env("MO_ALGO_SLICE_STREAM", algo_slice_stream.to_string())
        .env("MO_SOR_ROUTE_STREAM", sor_route_stream.to_string())
        .env("MO_SHM_PATH", shm_path)
        .env("MO_SHM_REGION_ID", shm_region_id.to_string())
        .env("MO_SHM_CAPACITY", shm_capacity.to_string())
        .env("MO_MD_SHM_PATH", md_shm_path)
        .env("MO_MD_SHM_CAPACITY", MD_MAX_SYMBOLS.to_string())
        .spawn()
        .expect("failed to spawn sor-marketdata service")
}

/// Wall-clock nanoseconds since UNIX epoch, matching what the SOR service stamps on each event.
#[inline]
fn wall_ns() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("time went backwards")
        .as_nanos() as u64
}

/// xorshift64 PRNG: deterministic, allocation-free jitter for tick prices.
#[inline]
fn jitter_price(seq: u64, base: u64) -> (u64, u64) {
    let mut x = seq.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
    x ^= x >> 30;
    let offset = (x % 11) as i64 - 5; // [-5, +5] ticks
    let bid = (base as i64 + offset - 1).max(1) as u64;
    let ask = (base as i64 + offset + 1).max(1) as u64;
    (bid, ask)
}

/// Publish one `MarketDataUpdate` tick then run the parent-order round trip.
///
/// The tick is published *before* the parent command so the MD service has the
/// best chance of having processed it by the time slices arrive at the SOR.
#[allow(clippy::too_many_arguments)]
fn run_one_parent_with_md(
    seq: u64,
    base_price: u64,
    md_pub: &mut AeronClusterPublisher,
    cmd_pub: &mut AeronClusterPublisher,
    route_sub: &mut AeronClusterSubscriber,
    algo: &mut Child,
    sor: &mut Child,
    md_svc: &mut Child,
    parent_hist: Option<&mut Histogram<u64>>,
    mut child_hist: Option<&mut Histogram<u64>>,
) {
    // 1. Publish a live tick with jittered bid/ask prices.
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
    // Best-effort publish: if the channel is back-pressured we skip and the SOR
    // will use the previous snapshot, which is acceptable.
    let _ = md_pub.publish(&tick.encode());

    // 2. Publish the parent order command.
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

    // 3. Collect all expected child route events.
    //    Child latency = wall_ns() at coordinator receive - send_timestamp_ns stamped by SOR
    //    before route_order. This is independent of batching and captures the true per-child
    //    routing latency (MD snapshot read + scoring + Aeron deliver).
    for _ in 0..EXPECTED_CHILDREN_PER_PARENT {
        let mut route_spins: u32 = 0;
        let bytes = loop {
            if let Some(b) = route_sub.poll() {
                break b;
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
        };
        if let Some(h) = child_hist.as_deref_mut() {
            let receive_ns = wall_ns();
            let latency = SorRouteRefEvent::decode(&bytes)
                .filter(|evt| evt.send_timestamp_ns > 0)
                .map(|evt| receive_ns.saturating_sub(evt.send_timestamp_ns))
                .unwrap_or(0);
            let _ = h.record(latency);
        }
    }

    if let Some(h) = parent_hist {
        let _ = h.record(parent_start.elapsed().as_nanos() as u64);
    }
}

fn bench_e2e_md_latency(c: &mut Criterion) {
    ensure_driver();
    let ws_root = workspace_root();
    ensure_service_binaries(&ws_root);

    let mut group = c.benchmark_group("e2e_algo_sor_marketdata_latency");
    group.measurement_time(configured_secs("MO_BENCH_MD_MEASUREMENT_SECS", 60));
    let wu_secs = std::env::var("MO_BENCH_MD_WARMUP_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(1)
        .max(1);
    group.warm_up_time(Duration::from_secs(wu_secs));
    group.sample_size(configured_sample_size());

    // ── Single scenario: e2e_s1_steady ──────────────────────────────────────
    let scenario_name = "e2e_s1_steady";
    let base_price: u64 = 1_500;
    let shm_capacity: usize = 64 << 20; // 64 MiB
    let shm_region_id: u32 = 1;

    group.throughput(Throughput::Elements(1));

    let base = next_stream_base(4); // parent_cmd, algo_slice, sor_route, market_data
    let parent_cmd_stream = STREAM_PARENT_CMD + base;
    let algo_slice_stream = STREAM_ALGO_SLICE + base;
    let sor_route_stream = STREAM_SOR_ROUTE + base;
    let md_stream = STREAM_MARKET_DATA + base;

    let pid = std::process::id();

    // Algo/SOR shared region (pre-created by coordinator)
    let shm_path = std::env::temp_dir()
        .join(format!("mo_rust_aeron_shm_{pid}_{base}_{scenario_name}.dat"))
        .to_string_lossy()
        .into_owned();
    let _shm_region = SharedRegion::create(&shm_path, shm_region_id, shm_capacity);

    // Market-data region (pre-created by coordinator; opened by md_svc and sor_md)
    let md_shm_path = std::env::temp_dir()
        .join(format!("mo_rust_aeron_md_{pid}_{base}_{scenario_name}.dat"))
        .to_string_lossy()
        .into_owned();
    let _md_region = MarketDataRegion::create(&md_shm_path, MD_MAX_SYMBOLS);

    // Spawn services
    let mut md_svc = spawn_marketdata_process(&ws_root, md_stream, &md_shm_path);
    let mut sor =
        spawn_sor_md_process(&ws_root, algo_slice_stream, sor_route_stream, &shm_path, shm_region_id, shm_capacity, &md_shm_path);
    let mut algo = spawn_algo_process(
        &ws_root,
        parent_cmd_stream,
        algo_slice_stream,
        &shm_path,
        shm_region_id,
        shm_capacity,
        base_price,
    );

    // Allow process startup and Aeron image establishment.
    thread::sleep(Duration::from_millis(400));
    for (name, child) in [("algo", &mut algo), ("sor", &mut sor), ("md_svc", &mut md_svc)] {
        if let Ok(Some(status)) = child.try_wait() {
            panic!("{name} service exited early: {status}");
        }
    }

    // Coordinator channels
    let mut md_pub =
        AeronClusterPublisher::new("aeron:ipc", md_stream).expect("md publisher");
    let mut cmd_pub =
        AeronClusterPublisher::new("aeron:ipc", parent_cmd_stream).expect("cmd publisher");
    let mut route_sub =
        AeronClusterSubscriber::new("aeron:ipc", sor_route_stream).expect("route subscriber");

    // ── Warmup ───────────────────────────────────────────────────────────────
    let mut seq = 0u64;
    for _ in 0..WARMUP_PARENTS {
        seq += 1;
        run_one_parent_with_md(
            seq, base_price, &mut md_pub, &mut cmd_pub, &mut route_sub,
            &mut algo, &mut sor, &mut md_svc, None, None,
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
                        seq, bp, &mut md_pub, &mut cmd_pub, &mut route_sub,
                        &mut algo, &mut sor, &mut md_svc,
                        Some(&mut parent_hist),
                        Some(&mut child_hist),
                    );
                }
                let wall = wall_start.elapsed();
                write_report(scenario_name, &parent_hist, &child_hist, samples, wall);
                let nanos =
                    wall.as_secs_f64() * 1e9 * iters as f64 / samples.max(1) as f64;
                Duration::from_nanos(nanos.min(u64::MAX as f64) as u64)
                    .max(Duration::from_nanos(1))
            });
        },
    );

    // ── Teardown ─────────────────────────────────────────────────────────────
    stop_child("md_svc", &mut md_svc);
    stop_child("sor", &mut sor);
    stop_child("algo", &mut algo);
    let _ = fs::remove_file(&shm_path);
    let _ = fs::remove_file(&md_shm_path);
    std::mem::drop(_shm_region);
    std::mem::drop(_md_region);

    group.finish();
}

criterion_group!(benches, bench_e2e_md_latency);
criterion_main!(benches);

