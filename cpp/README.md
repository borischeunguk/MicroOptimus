# C++ Modules (Rust-like Layout)

This directory contains standalone C++ MVP modules, separated from Java and legacy liquidator C++ code.

## Modules

- `common/` shared C++ types and benchmark helpers
- `algo/` VWAP algo implementation and benchmark
- `sor/` SOR MVP implementation, router benchmark, and e2e benchmark

## Build

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

## Smoke benchmarks

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/cpp
./build/algo/vwap_latency --mode smoke --scenario algo_s1_steady
./build/sor/router_latency --mode smoke --scenario sor_s1_steady
./build/sor/e2e_algo_sor_latency --mode smoke --scenario e2e_s1_steady
```

## Full benchmarks

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/cpp
./build/algo/vwap_latency --mode full --samples 1000000 --scenario algo_s1_steady
./build/sor/router_latency --mode full --samples 1000000 --scenario sor_s1_steady
./build/sor/e2e_algo_sor_latency --mode full --samples 1000000 --scenario e2e_s1_steady
```

## Perf reports

- `cpp/algo/perf-reports/cpp_vwap_latency_<scenario>.json`
- `cpp/sor/perf-reports/cpp_router_latency_<scenario>.json`
- `cpp/sor/perf-reports/cpp_e2e_algo_sor_latency_<scenario>.json`

