# Rust Module IPC (Real Aeron + Shared Mmap)

This Rust workspace now supports both:

- in-memory cluster topic (default fast test mode)
- real Aeron IPC via `rusteron-client` + embedded Aeron C media driver (feature-gated)

## Design

- Control plane: `common::cluster`
  - default: `InMemoryClusterTopic`
  - real Aeron: `AeronClusterPublisher` / `AeronClusterSubscriber`
- Envelope/messages: `common::sbe`
- Data plane: one shared region in `common::shm::SharedRegion`
- Algo adapter: `algo::cluster_service::AlgoClusterService`
- Sor adapter: `sor::cluster_service::SorClusterService`
- Cross-process service binaries:
  - `algo/src/bin/algo_aeron_service.rs`
  - `sor/src/bin/sor_aeron_service.rs`

## Local verification

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
cargo test -p common -p algo -p sor
```

## Real Aeron verification

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
MO_AERON_DIR=/tmp/microoptimus_aeron_test cargo test -p sor --features aeron-integration parent_to_route_flow_over_real_aeron_ipc -- --nocapture
```

## Real Aeron benchmark smoke run (s1 only)

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
MO_BENCH_SAMPLES=10 MO_BENCH_WARMUP_SECS=1 MO_BENCH_MEASUREMENT_SECS=1 MO_BENCH_CRITERION_SAMPLE_SIZE=10 MO_BENCH_HOP_TIMEOUT_SECS=20 cargo bench -p algo --bench vwap_latency --features aeron-integration -- --noplot algo_s1_steady
MO_BENCH_SAMPLES=10 MO_BENCH_WARMUP_SECS=1 MO_BENCH_MEASUREMENT_SECS=1 MO_BENCH_CRITERION_SAMPLE_SIZE=10 MO_BENCH_HOP_TIMEOUT_SECS=20 cargo bench -p sor --bench router_latency --features aeron-integration -- --noplot sor_s1_steady
MO_BENCH_SAMPLES=10 MO_BENCH_WARMUP_SECS=1 MO_BENCH_MEASUREMENT_SECS=1 MO_BENCH_CRITERION_SAMPLE_SIZE=10 MO_BENCH_HOP_TIMEOUT_SECS=20 cargo bench -p sor --bench e2e_algo_sor_latency --features aeron-integration -- --noplot e2e_s1_steady
```

Reports (overwritten in-place):

- `algo/perf-reports/rust_aeron_vwap_latency_algo_s1_steady.json`
- `sor/perf-reports/rust_aeron_router_latency_sor_s1_steady.json`
- `sor/perf-reports/rust_aeron_e2e_algo_sor_latency_e2e_s1_steady.json`

E2E tests:

- `sor/tests/e2e_cluster_flow_test.rs` (in-memory)
- `sor/tests/e2e_aeron_cluster_flow_test.rs` (real Aeron, feature-gated)

