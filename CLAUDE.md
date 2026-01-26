# CLAUDE.md

Always ask me questions to clarify the product requirements, technical requirements, hard constraints
 and engineering principles before writing any code.

Always create a new pr if major changes are required after the plan session

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MicroOptimus is an ultra-low-latency trading system targeting sub-microsecond tick-to-trade latency. It implements a high-performance order matching engine and Smart Order Router (SOR) with support for multiple sequencing technologies (LMAX Disruptor, Aeron Cluster, Coral Blocks).

**Achieved Performance:**
- Average Latency: 159ns (C++ SOR)
- P99 Latency: 211ns
- Throughput: 4.87M orders/sec

**Implementation Principles:** GC-free, lock-free, zero-copy

## Build Commands

```bash
# Build all modules
./gradlew buildAll

# Build specific module
./gradlew :osm:build
./gradlew :common:build
./gradlew :internaliser:build
./gradlew :algo:build

# Run all tests
./gradlew testAll

# Run tests for specific module
./gradlew :osm:test
./gradlew :internaliser:test

# Run single test class
./gradlew :internaliser:test --tests "com.microoptimus.internaliser.InternalMatchingEngineTest"

# Run JMH benchmarks
./gradlew :osm:jmh
./gradlew :internaliser:jmh

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

### System Architecture

```
                    ┌─────────────────────────────────┐
                    │     Aeron Cluster Sequencer     │
                    │  (Global Ordering - All Flows)  │
                    └────────────────┬────────────────┘
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        │                            │                            │
        ▼                            ▼                            ▼
┌───────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  DMA Orders   │          │   Algo Orders   │          │ Principal Flow  │
│  (Gateway)    │          │   (Gateway)     │          │   (Signal)      │
└───────┬───────┘          └────────┬────────┘          └────────┬────────┘
        │                           │                            │
        │                           ▼                            │
        │                  ┌─────────────────┐                   │
        │                  │   Algo Engine   │                   │
        │                  │ VWAP/TWAP/ICE   │                   │
        │                  │ generates slices│                   │
        │                  └────────┬────────┘                   │
        │                           │                            │
        ▼                           ▼                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         OSM (Smart Order Router)                        │
│   - Receives: DMA orders, Algo slices, Principal quotes                 │
│   - Routes to: INTERNAL (internaliser) or EXTERNAL (liquidator)         │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
         ┌───────────────────────┼───────────────────────┐
         ▼                       ▼                       ▼
   ┌──────────────┐       ┌──────────┐           ┌──────────┐
   │ INTERNALISER │       │   CME    │           │  NASDAQ  │
   │ (internal OB │       │ (iLink3) │           │  (OUCH)  │
   │ + matching)  │       └──────────┘           └──────────┘
   └──────────────┘
```

### Modules

| Module | Purpose |
|--------|---------|
| **common** | SBE schemas, cluster services, shared memory, types |
| **osm** | Smart Order Router logic, routing decisions, venue scoring |
| **algo** | Algorithmic execution (VWAP, TWAP, Iceberg), slice generation |
| **internaliser** | Internal orderbook, matching engine, GC-free order/price-level pooling |
| **signal** | Principal Trading Book, market-making strategy, quote generation |
| **liquidator** | External venue connectivity (CME iLink3, Nasdaq OUCH), C++ JNI |
| **recombinor** | Market data processing, unified book aggregation |
| **gateway** | Order entry, FIX/REST parsing |
| **app** | Application wiring |

### Order Flow Types

1. **DMA Orders** - Direct Market Access orders (Market, Limit, Stop, Stop-Limit) with IOC/GTC/DAY TIF
   - Gateway → Aeron Cluster → OSM (SOR) → INTERNAL or EXTERNAL

2. **Algo Orders** - Algorithmic execution (VWAP, TWAP, Iceberg)
   - Gateway → Aeron Cluster → Algo Engine → Slices → OSM (SOR) → INTERNAL or EXTERNAL

3. **Principal Flow** - Market-making quotes from unified book
   - Recombinor → Signal (QuoteGenerator) → Risk Check → OSM (SOR) → INTERNAL or EXTERNAL

### Three Development Phases

1. **MVP** - Single-process with LMAX Disruptor, inter-thread RingBuffers
2. **Standard** - Multi-process with Aeron Cluster for global sequencing (3-node Raft)
3. **Advanced** - Full C++ with Coral Blocks, lock-free/GC-free

### Key Patterns

- **Object Pooling** - GC-free Order and PriceLevel objects with init()/reset() pattern
- **Intrusive Linked Lists** - No Node wrappers, objects contain their own prev/next pointers
- **Zero-Copy Shared Memory** - VenueTOBStore, SharedMemoryStore for cross-process data
- **SBE Encoding** - Simple Binary Encoding for Java to C++ communication
- **Multi-factor Venue Scoring** - Price, latency, fill rate, fees for routing decisions

## Key Technologies

- **Java 17+**, Gradle build
- **LMAX Disruptor 4.0.0** - High-performance ring buffers
- **Aeron 1.44.1** - Reliable messaging + cluster consensus
- **SBE 1.30.0** - Zero-copy binary serialization
- **Agrona 1.21.1** - Lock-free collections (Long2ObjectHashMap, etc.)
- **HdrHistogram** - Latency measurement
- **C++17** - Smart Order Router with CMake build, Boost, Folly
- **CoralME 1.10.2** - GC-free orderbook patterns ( To be further leveraged )

## SBE Schema Development

Schemas are in `common/src/main/sbe/orders/`:
- `OrderRequestMessage.xml` - Core order messages with OrderFlowType (DMA, PRINCIPAL, ALGO_SLICE)
- `AlgoMessages.xml` - Algo order requests, slices, commands, status updates
- `InternalMessages.xml` - Internal matching engine messages

After editing schemas:

```bash
./gradlew :common:generateSbe
```

Generated Java codecs appear in `common/src/main/java/com/microoptimus/common/sbe/orders/`.

## Performance Constraints

- **Sequencer based architecture ( Disrupter/Aeron/Coral, global/market data + orders/fills)
- **NO JNI between java and C++ communication, ideally should all memory mapping based
- **Zero GC in hot paths** - all temporary objects must be pooled
- **Lock-free data structures** - use Agrona collections (Long2ObjectHashMap, etc.)
- **Target latencies**: <500ns for matching, <500ns for routing
- **Verify with**: `./gradlew :internaliser:jmh -Pjmh.jvmArgs="-verbose:gc"` (target: zero GC output)

## Module Dependencies

```
common (SBE, cluster, shm, types)
│
├── internaliser → common
│
├── liquidator → common (external venue connectivity)
│
├── osm → common, internaliser, liquidator (SOR logic)
│
├── algo → common, osm (algo execution)
│
├── signal → common, osm, recombinor (principal/market-making)
│
├── recombinor → common (market data, unified book)
│
├── gateway → common
│
└── app → all modules
```

## Key Source Directories

```
common/
├── src/main/sbe/orders/          # SBE XML schemas
├── src/main/java/.../sbe/orders/ # Generated SBE codecs
├── src/main/java/.../types/      # Core types (Side, OrderType, TimeInForce)
├── src/main/java/.../cluster/    # Aeron Cluster services
└── src/main/java/.../shm/        # Shared memory utilities

internaliser/
└── src/main/java/.../internaliser/
    ├── Order.java                # Pooled order with OrderFlowType
    ├── PriceLevel.java           # Pooled price level (intrusive linked list)
    ├── OrderPool.java            # Object pool for orders
    ├── InternalOrderBook.java    # GC-free orderbook
    └── InternalMatchingEngine.java # Price-time priority matching

osm/
└── src/main/java/.../osm/
    ├── sor/                      # Smart Order Router
    │   ├── SmartOrderRouter.java
    │   ├── VenueScorer.java
    │   ├── OrderSplitter.java
    │   └── RiskManager.java
    └── routing/                  # Routing implementations
        ├── InternalRouter.java
        └── ExternalRouter.java

algo/
└── src/main/java/.../algo/
    ├── algorithms/               # VWAP, TWAP, Iceberg
    ├── engine/                   # AlgoEngine orchestrator
    ├── model/                    # AlgoOrder, AlgoParameters
    └── slice/                    # Slice tracking

signal/
└── src/main/java/.../signal/
    ├── principal/                # PrincipalTradingBook, QuoteGenerator
    ├── strategy/                 # MarketMakingStrategy, InventoryManager
    └── cluster/                  # SignalClusterService

liquidator/
├── src/main/java/.../liquidator/ # Java JNI wrapper
└── src/main/cpp/                 # C++ SOR implementation
```