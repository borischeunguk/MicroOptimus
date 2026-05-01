# sor crate

This crate contains the Rust MVP smart order router (SOR) components for MicroOptimus.

## Benchmarks

Criterion benchmarks are provided for SOR-only and end-to-end algo+SOR latency.

- SOR-only benchmark: `benches/router_latency.rs`
- End-to-end benchmark: `benches/e2e_algo_sor_latency.rs`
- Report output: `perf-reports/*.json`
- Metrics include p90/p99/p99.9 latency and throughput.

## Scenarios (v1)

The benchmark suite currently uses simplified DMA market profiles for:

- `CME`
- `NASQ`

SI, dark pool, and broker profiles are intentionally deferred for later phases.

### Run

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust/sor
cargo test
cargo bench --bench router_latency
cargo bench --bench e2e_algo_sor_latency
```

### Quick smoke run

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust/sor
cargo bench --bench router_latency -- --warm-up-time 0.5 --measurement-time 1
cargo bench --bench e2e_algo_sor_latency -- --warm-up-time 0.5 --measurement-time 1
```

