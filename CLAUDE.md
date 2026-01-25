# CLAUDE.md

Always ask me questions to clarify the product requirements, technical requirements, hard constraints 
 and engineering principles before writing any code.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MicroOptimus is an ultra-low-latency trading system targeting sub-microsecond tick-to-trade latency. It implements a high-performance order matching engine and Smart Order Router (SOR) with support for multiple sequencing technologies (LMAX Disruptor, Aeron Cluster, Coral Blocks).

**Achieved Performance:**
- Average Latency: 159ns (C++ SOR)
- P99 Latency: 211ns
- Throughput: 4.87M orders/sec

## Build Commands

```bash
# Build all modules
./gradlew buildAll

# Build specific module
./gradlew :osm:build
./gradlew :common:build

# Run all tests
./gradlew testAll

# Run tests for specific module
./gradlew :osm:test

# Run single test class
./gradlew :osm:test --tests "com.microoptimus.osm.OrderBookTest"

# Run JMH benchmarks
./gradlew :osm:jmh

# Generate SBE codecs from schema
./gradlew :common:generateSbe

# Build C++ Smart Order Router
cd liquidator/src/main/cpp
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make

# Run C++ performance test
./sor_perf_test
```

## Architecture

### Modules

| Module | Purpose |
|--------|---------|
| **common** | Shared types, SBE schemas, Aeron Cluster services, shared memory utilities |
| **osm** | Order State Manager - orderbook + matching engine (CoralME patterns) |
| **liquidator** | Order gateway to exchanges, C++ Smart Order Router via JNI |
| **recombinor** | Market data processor - combines internal + external market data |
| **gateway** | Market data inbound (UDP/SBE decoder) |
| **signal** | Market making strategy module |
| **app** | Main application launcher |

### Data Flow (Tick-to-Trade ~950ns)

```
CME Market Data (UDP) → Gateway (~100ns)
    → Recombinor (~200ns) → Signal (~150ns)
    → OSM Matching (~200ns) → Liquidator (~300ns)
    → CME Exchange
```

### Three Development Phases

1. **MVP** - Single-process with LMAX Disruptor, inter-thread RingBuffers
2. **Standard** - Multi-process with Aeron Cluster for global sequencing (3-node Raft)
3. **Advanced** - Full C++ with Coral Blocks, lock-free/GC-free

### Key Patterns

- **Unified OrderBook** - Single orderbook with priority: INTERNAL > SIGNAL > EXTERNAL
- **Object Pooling** - GC-free Order and PriceLevel objects (CoralME patterns)
- **Zero-Copy Shared Memory** - VenueTOBStore, SharedMemoryStore for cross-process data
- **SBE Encoding** - Simple Binary Encoding for Java ↔ C++ communication

## Key Technologies

- **Java 17+**, Gradle build
- **LMAX Disruptor 4.0.0** - High-performance ring buffers
- **Aeron 1.44.1** - Reliable messaging + cluster consensus
- **SBE 1.30.0** - Zero-copy binary serialization
- **CoralME 1.10.2** - GC-free orderbook patterns
- **C++17** - Smart Order Router with CMake build

## SBE Schema Development

Schemas are in `common/src/main/sbe/orders/`. After editing:

```bash
./gradlew :common:generateSbe
```

Generated Java codecs appear in `common/src/main/java/com/microoptimus/common/sbe/orders/`.

## Performance Constraints

- **Zero GC in hot paths** - all temporary objects must be pooled
- **Lock-free data structures** - use Agrona collections (LongMap, etc.)
- **Verify with**: `./gradlew :osm:jmh -Pjmh.jvmArgs="-verbose:gc"` (target: zero GC output)

## Module Dependencies

```
app
├── osm → common, liquidator
├── liquidator → common (JNI to C++)
├── recombinor → common
├── signal → common
└── gateway → common
```
