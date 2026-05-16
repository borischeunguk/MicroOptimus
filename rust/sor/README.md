# sor crate

This crate contains the Rust MVP smart order router (SOR) components for MicroOptimus.

## Benchmarks

Criterion benchmarks are provided for SOR-only and end-to-end algo+SOR latency.

- SOR-only benchmark: `benches/router_latency.rs`
- End-to-end benchmark: `benches/e2e_algo_sor_latency.rs`
- Report output: `perf-reports/rust_aeron_*.json`
- Metrics include p90/p99/p99.9 latency and throughput for both parent orders and child slices.

## E2E benchmark design

The e2e benchmark mirrors the Java MVP `E2EAlgoSorLatencyJmh` exactly for apples-to-apples comparison:

| Parameter | Value | Matches Java |
|-----------|-------|-------------|
| `total_quantity` | 40,000 | ✅ |
| `num_buckets` | 12 | ✅ |
| `participation_rate` | 0.12 | ✅ |
| `tick_step_ns` | 40,000 | ✅ |
| `end_ns` | 8,000,000 | ✅ |
| Children/parent | **100** (VWAP slices) | ✅ |
| Process lifecycle | Spawned **once per scenario** | ✅ `@Setup(Level.Trial)` |
| Child latency | Timer starts **before** poll | ✅ includes queue wait + SOR hop |
| Warmup | 200 parent orders before measurement | ✅ |

With 100k samples: **100k parents × 100 children = 10M child route events**.

## Scenarios (v1)

- `e2e_s1_steady` — steady-state price 1,500
- `e2e_s2_open_burst` — burst price 1,505
- `e2e_s3_thin_liquidity` — thin liquidity price 1,495
- `e2e_s4_large_parent` — large parent price 1,510

### Full 100k run (Java-comparable, ~7 min)

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
MO_BENCH_SAMPLES=100000 MO_BENCH_CRITERION_SAMPLE_SIZE=10 MO_BENCH_MEASUREMENT_SECS=3500 MO_BENCH_HOP_TIMEOUT_SECS=600 \
  cargo bench -p sor --bench e2e_algo_sor_latency --features aeron-integration -- e2e_s1_steady
```

### Smoke run (10 parents, fast)

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
MO_BENCH_SAMPLES=10 MO_BENCH_CRITERION_SAMPLE_SIZE=10 MO_BENCH_MEASUREMENT_SECS=5 \
  cargo bench -p sor --bench e2e_algo_sor_latency --features aeron-integration -- e2e_s1_steady
```

### SOR-only benchmark

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust/sor
cargo bench --bench router_latency
```

### Kill leftover service processes

```bash
ps aux | grep -E "algo_aeron_service|sor_aeron_service" | grep -v grep
kill -9 <pid>
```

