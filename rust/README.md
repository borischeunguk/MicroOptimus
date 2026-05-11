# Rust Module IPC (Aeron Wrapper MVP)

This Rust workspace now includes a single-node cluster-style control plane plus a shared mmap data plane for `algo` -> `sor` communication.

## Design

- Control plane: `common::cluster` (`InMemoryClusterTopic` as Aeron-wrapper MVP stand-in)
- Envelope/messages: `common::sbe`
- Data plane: one shared region in `common::shm::SharedRegion`
- Algo adapter: `algo::cluster_service::AlgoClusterService`
- Sor adapter: `sor::cluster_service::SorClusterService`

## Local verification

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/rust
cargo test -p common -p algo -p sor
```

The e2e flow test is in:

- `sor/tests/e2e_cluster_flow_test.rs`

