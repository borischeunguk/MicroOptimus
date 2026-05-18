# javamvp

Java MVP implementation for real Aeron Cluster sequencing + SBE-style fixed messages + shared memory mmap IPC between `algo` and `sor`.

## Modules

- `common` shared real cluster harness, shm, and message codecs
- `algo` JMH VWAP benchmark
- `sor` JMH router and e2e benchmarks

## Build (Maven default)

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/javamvp
mvn -q clean install -DskipTests
```

## Run benchmarks (Maven, separate commands)

`exec:java` can miss JMH classes in forked JVMs on some setups. Use Maven to build runtime classpath, then launch JMH with `java -cp`.

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/javamvp/algo
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/jmh.cp -Dmdep.includeScope=runtime
CP="$(cat target/jmh.cp):target/classes"
java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Djavamvp.samples=1000000 -Djmh.ignoreLock=true \
  -cp "$CP" org.openjdk.jmh.Main com.microoptimus.javamvp.algo.jmh.VwapLatencyJmh \
  -wi 0 -i 1 -f 1 -bm avgt -tu ns -foe true
```

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/javamvp/sor
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/jmh.cp -Dmdep.includeScope=runtime
CP="$(cat target/jmh.cp):target/classes"
java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Djavamvp.samples=1000000 -Djmh.ignoreLock=true \
  -cp "$CP" org.openjdk.jmh.Main com.microoptimus.javamvp.sor.jmh.RouterLatencyJmh \
  -wi 0 -i 1 -f 1 -bm ss -tu ns -foe true
```

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/javamvp/sor
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/jmh.cp -Dmdep.includeScope=runtime
CP="$(cat target/jmh.cp):target/classes"
java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Djavamvp.samples=1000000 -Djavamvp.e2e.samples=1000000 -Djmh.ignoreLock=true \
  -cp "$CP" org.openjdk.jmh.Main com.microoptimus.javamvp.sor.jmh.E2EAlgoSorLatencyJmh \
  -wi 0 -i 1 -f 1 -bm ss -tu ns -foe true
```

## Gradle compatibility

Gradle build files are intentionally kept for compatibility with existing workflows:

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp clean build
```

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp clean build
./gradlew -p javamvp :algo:runVwapLatency :sor:runRouterLatency :sor:runE2ELatency
```

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp clean build
./gradlew -p javamvp :sor:runE2ELatency -PjavamvpE2eSamples=100000 -Djavamvp.e2e.timeout.ns=50000000 --rerun-tasks
```

## Expected reports

- `javamvp/algo/perf-reports/java_aeron_vwap_latency_algo_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_router_latency_sor_s1_steady.json`
- `javamvp/sor/perf-reports/java_aeron_e2e_algo_sor_latency_e2e_s1_steady.json`
