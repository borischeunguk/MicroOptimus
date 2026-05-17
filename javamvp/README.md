# javamvp

Java MVP implementation for real Aeron Cluster sequencing + SBE-style fixed messages + shared memory mmap IPC between `algo` and `sor`.

## Modules

- `common` shared real cluster harness, shm, and message codecs
- `algo` JMH VWAP benchmark
- `sor` JMH router and e2e benchmarks

## Run benchmarks

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp :algo:runVwapLatency :sor:runRouterLatency :sor:runE2ELatency
```

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp clean build
./gradlew -p javamvp :sor:runE2ELatency -PjavamvpE2eSamples=100000 -Djavamvp.e2e.timeout.ns=50000000
```
Run RUST_PARITY or BATCH_BENCH modes:

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
JAVA_TOOL_OPTIONS='-Djavamvp.algo.emission.mode=RUST_PARITY' ./gradlew -p javamvp :sor:runE2ELatency -PjavamvpE2eSamples=100000 -Djavamvp.e2e.timeout.ns=50000000 --rerun-tasks
JAVA_TOOL_OPTIONS='-Djavamvp.algo.emission.mode=BATCH_BENCH' ./gradlew -p javamvp :sor:runE2ELatency -PjavamvpE2eSamples=100000 -Djavamvp.e2e.timeout.ns=50000000 --rerun-tasks
```

## Expected reports

- `javamvp/algo/perf-reports/java_aeron_vwap_latency_algo_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_router_latency_sor_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_e2e_algo_sor_latency_e2e_s1_steady.json`
