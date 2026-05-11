# javamvp

Java MVP implementation for Aeron-style sequencer + SBE-style fixed messages + shared memory mmap IPC between `algo` and `sor`.

## Modules

- `common` shared sequencing, shm, and message codecs
- `algo` VWAP benchmark runner
- `sor` router and e2e benchmark runners

## Run benchmarks

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp :algo:runVwapLatency :sor:runRouterLatency :sor:runE2ELatency
```

## Expected reports

- `javamvp/algo/perf-reports/java_aeron_vwap_latency_algo_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_router_latency_sor_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_e2e_algo_sor_latency_e2e_s1_steady.json`

