# algo crate

This crate contains Rust MVP algorithmic execution components for MicroOptimus.

## Benchmarks

Criterion benchmarks are provided for VWAP parent-to-child latency and throughput.

- Benchmark file: `benches/vwap_latency.rs`
- Report output: `perf-reports/rust_aeron_vwap_latency_<scenario>.json`
- Metrics: `latency_ns_p90`, `latency_ns_p99`, `latency_ns_p999`, parent/sec, child/sec
- Minimum parent samples per scenario: `1,000,000` (for stable comparisons)

### Run

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust/algo
cargo test
cargo bench --bench vwap_latency
```

### Quick smoke run

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust/algo
cargo bench --bench vwap_latency -- --warm-up-time 0.5 --measurement-time 1
```

Note: benchmark code enforces at least `1,000,000` parent iterations per scenario, so full runs are intentionally longer.

