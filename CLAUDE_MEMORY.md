# Claude Session Memory - MicroOptimus Project

**Last Updated:** December 9, 2025  
**Session:** Implementation Planning - 3 Version Strategy + Tick-to-Trade Flow

---

## Project Context

**Goal:** Ultra-low-latency trading system (CME Exchange model)

**Components:**
- Order Gateway (C++), Market Data Gateway (C++)
- Matching Engine (Java), Market Data Processor (Java), Market Making (Java)
- Sequencer: LMAX Disruptor → Aeron → Coral Blocks

**Protocols:** FIX + SBE (Simple Binary Encoding preferred)

**3 Development Phases:**
1. **MVP** - Single-process Java with LMAX Disruptor (<2μs, >10M orders/sec)
2. **Standard** - Multi-process with Aeron Cluster
3. **Advanced** - Full C++ with Coral Blocks (<500ns, >30M orders/sec)

---

## TICK-TO-TRADE FLOW

**End-to-End Target: ~1μs (1,000 nanoseconds)**

### Complete Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                  CME MARKET DATA (UDP Multicast)                │
│                         iLink3 / MDP 3.0                        │
└────────────────────────────────┬────────────────────────────────┘
                                 │ Network
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      GATEWAY (Market Data)                      │
│  Thread-1, CPU-0 | C++ UDP Receiver | SBE Decoder              │
│  - Capture raw UDP packets                                      │
│  - Decode SBE-encoded market data                               │
│  - Normalize to internal format                                 │
│  Latency: ~100ns                                                │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                        [RB-1: MarketDataEvent]
                                 │ Disruptor/CoralRing
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                   RECOMBINOR (Market Data Processor)            │
│  Thread-2, CPU-1 | Java/C++ | Book Reconstruction              │
│  - Aggregate incremental updates                                │
│  - Maintain top-of-book (BBO)                                   │
│  - Detect crosses/locks                                         │
│  Latency: ~200ns                                                │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                        [RB-2: BookUpdateEvent]
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                    ▼                         ▼
┌─────────────────────────────┐   ┌─────────────────────────────┐
│   OSM (Market State Copy)   │   │   SIGNAL (Market Making)    │
│   Optional: Passive viewer  │   │   Thread-3, CPU-2           │
│   For risk/monitoring       │   │   - Strategy logic          │
└─────────────────────────────┘   │   - Quote generation        │
                                  │   - Inventory management    │
                                  │   Latency: ~150ns           │
                                  └──────────┬──────────────────┘
                                             │
                                [RB-3: OrderRequestEvent]
                                             │ Disruptor/CoralRing
                                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     OSM (Order & Matching Engine)               │
│  Thread-4, CPU-3 | Java (MVP) / C++ (Advanced) | **CRITICAL**  │
│  - Receive order requests                                       │
│  - Price-time priority matching                                 │
│  - Generate executions                                          │
│  - Update internal book state                                   │
│  Latency: ~200ns                                                │
└────────────────────────────┬────────────────────────────────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
      [RB-4: Exec]    [RB-5: Exec]    [RB-6: Exec]
            │                │                │
            ▼                ▼                ▼
┌────────────────┐ ┌─────────────┐ ┌──────────────────────────┐
│  RECOMBINOR    │ │   SIGNAL    │ │     LIQUIDATOR           │
│  (Book Update) │ │  (Position  │ │  Thread-5, CPU-4         │
│  Internal book │ │   & P&L)    │ │  - Risk checks           │
│  reflects exec │ │  Update inv │ │  - FIX/iLink3 encoding   │
└────────────────┘ └─────────────┘ │  - Route to CME          │
                                   │  Latency: ~300ns         │
                                   └──────────┬───────────────┘
                                              │ Network
                                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       CME EXCHANGE (iLink3)                     │
│                      External Order Gateway                      │
└─────────────────────────────────────────────────────────────────┘
```

---

### Latency Breakdown (Target)

| **Stage** | **Component** | **Operation** | **Latency** | **Cumulative** |
|-----------|---------------|---------------|-------------|----------------|
| 1 | Gateway | UDP capture + SBE decode | 100 ns | 100 ns |
| 2 | Recombinor | Book reconstruction | 200 ns | 300 ns |
| 3 | Signal | Strategy logic + quote gen | 150 ns | 450 ns |
| 4 | OSM | Order matching | 200 ns | 650 ns |
| 5 | Liquidator | FIX encode + send | 300 ns | 950 ns |
| **Total** | **Gateway → Exchange** | **Tick-to-Trade** | **~950 ns** | **< 1 μs** |

**Note:** Network latencies not included (Gateway receive, Liquidator send)

---

### Critical Path Analysis

#### Fastest Path (Market Making):
```
Market Data → Recombinor → Signal → OSM → Liquidator → CME
   100ns        200ns       150ns    200ns    300ns    = 950ns
```

#### Measurement Points:

1. **Gateway Timestamp**: UDP packet arrival (hardware timestamp if available)
2. **Recombinor Timestamp**: Book update published to RB-2
3. **Signal Timestamp**: Order request published to RB-3
4. **OSM Timestamp**: Order accepted, execution generated
5. **Liquidator Timestamp**: FIX message sent to network

#### Key Optimizations:

- **Thread Affinity**: Pin threads to dedicated CPUs (avoid context switches)
- **CPU Isolation**: Use `isolcpus` kernel parameter (Linux)
- **NUMA Awareness**: Keep data on same NUMA node
- **Busy Spin**: No blocking waits (BusySpinWaitStrategy)
- **GC-Free**: Object pooling, zero allocation in hot path
- **Cache Locality**: Sequential memory access, cache-line padding
- **Lock-Free**: Disruptor sequencer, CoralRing, atomic operations

---

### Ring Buffer Configuration (Version 1: Disruptor)

| **Ring Buffer** | **Producer** | **Consumer** | **Event Type** | **Size** | **Wait Strategy** |
|-----------------|--------------|--------------|----------------|----------|-------------------|
| RB-1 | Gateway | Recombinor | MarketDataEvent | 2048 | BusySpin |
| RB-2 | Recombinor | Signal + OSM | BookUpdateEvent | 2048 | BusySpin |
| RB-3 | Signal | OSM | OrderRequestEvent | 2048 | BusySpin |
| RB-4 | OSM | Recombinor | ExecutionEvent | 2048 | BusySpin |
| RB-5 | OSM | Signal | ExecutionEvent | 2048 | BusySpin |
| RB-6 | OSM | Liquidator | ExecutionEvent | 2048 | BusySpin |

**Note:** Version 1 uses RB-2 and RB-3 only (simplified MVP)

---

### Thread & CPU Pinning Strategy

```bash
# Linux: Isolate CPUs 0-7 from kernel scheduler
isolcpus=0-7 nohz_full=0-7 rcu_nocbs=0-7

# Pin threads to CPUs
Thread-1 (Gateway)     → CPU 0 (NUMA Node 0)
Thread-2 (Recombinor)  → CPU 1 (NUMA Node 0)
Thread-3 (Signal)      → CPU 2 (NUMA Node 0)
Thread-4 (OSM)         → CPU 3 (NUMA Node 0) ← CRITICAL
Thread-5 (Liquidator)  → CPU 4 (NUMA Node 0)

# Reserve CPUs 5-7 for other processes (monitoring, logging)
```

---

### JVM Tuning (Java Components)

```bash
# Disable GC during trading hours
-XX:+UnlockExperimentalVMOptions
-XX:+UseEpsilonGC                    # No-op GC (requires manual heap sizing)
# OR
-XX:+UseZGC -XX:ZCollectionInterval=3600  # Minimal GC impact

# JIT compilation
-XX:+TieredCompilation
-XX:TieredStopAtLevel=1              # C1 compiler only (faster warmup)
# OR
-XX:-TieredCompilation               # C2 only (best performance after warmup)

# NUMA
-XX:+UseNUMA

# Large pages
-XX:+UseLargePages
-XX:LargePageSizeInBytes=2M

# Thread priorities
-XX:+UseThreadPriorities
-XX:ThreadPriorityPolicy=1
```

---

### Performance Targets by Version

| **Version** | **Technology** | **Target Latency** | **Target Throughput** |
|-------------|----------------|--------------------|-----------------------|
| V1 (MVP) | LMAX Disruptor (Java) | < 2 μs | > 10M msgs/sec |
| V2 (Standard) | Aeron IPC (Java) | < 5 μs | > 5M msgs/sec |
| V3 (Advanced) | CoralRing (C++) | < 500 ns | > 30M msgs/sec |

---

### Monitoring & Metrics

**Real-time Metrics (Captured in Hot Path):**
- Timestamp at each stage (hardware TSC if available)
- Queue depth (RingBuffer cursors)
- Drop count (if any)

**Post-trade Metrics (Logged Off Hot Path):**
- Latency histograms (HDR Histogram)
- Percentiles: P50, P95, P99, P99.9, P99.99
- Throughput per second
- CPU utilization per thread
- Memory allocation rate (should be ~0)

**Instrumentation:**
```java
// Example: Capture timestamp in event
event.setTimestamp(System.nanoTime());

// Or hardware timestamp counter
event.setTimestamp(getTSC());
```


## Progress Summary

✅ Reviewed project objectives  
✅ Cloned reference repos: LMAX Disruptor, Aeron, CoralME, CoralPool, CoralQueue, CoralRing  
✅ Analyzed LMAX Disruptor architecture  
✅ Analyzed CoralME matching engine  
✅ Analyzed Aeron messaging system  
✅ Compared all three technologies  
✅ Implemented V1 (LMAX Disruptor 3-stage pipeline)  
✅ Implemented V2 Prototype (Aeron IPC + Shared Memory Zero-Copy)  
✅ Implemented V2 Global Sequencer (Aeron Cluster + Shared Memory)

---

## LMAX Disruptor Summary

**What:** High-performance inter-thread messaging via ring buffer

**Core Components:**
- RingBuffer (pre-allocated, GC-free)
- Sequencer (SingleProducer/MultiProducer coordination)
- Sequence (cache-line padded atomic counter)
- EventHandler (consumer interface)
- WaitStrategy (BusySpin for lowest latency)

**Key Techniques:**
- Power-of-2 sizing, cache-line padding, event pre-allocation, lock-free

**Use Case:** Single-process, inter-thread messaging  
**Latency:** Sub-microsecond  
**Phase:** MVP (Phase 1)

---

## Aeron Summary

**What:** Efficient reliable messaging for UDP unicast/multicast and IPC

**Core Capabilities:**
- Multi-transport (IPC, UDP unicast, UDP multicast)
- Multi-language (Java, C, C++, .NET)
- Aeron Archive (durable stream recording/replay)
- Aeron Cluster (fault-tolerant Raft consensus)
- SBE integration for fast encoding

**Key Components:**
- Media Driver (manages transport)
- Publication/Subscription (producer/consumer)
- Log Buffers (ring buffer-based)
- Archive (persistence layer)
- Cluster (replicated state machines)

**Use Case:** Multi-process, IPC, network, persistence, fault tolerance  
**Latency:** Microseconds (IPC), milliseconds (network)  
**Phase:** Standard (Phase 2)

---

## CoralME Summary

**What:** Garbage-free, fast matching engine order book in Java

**Key Features:**
- Price-time priority matching
- Object pooling (Orders, PriceLevels)
- Callback-oriented (OrderBookListener)
- MARKET/LIMIT, IOC/GTC/DAY
- Book states: NORMAL/CROSSED/LOCKED/ONESIDED/EMPTY
- Trade-to-self prevention

**Core Structure:**
- Doubly-linked price levels (sorted)
- Doubly-linked orders per level (time priority)
- LongMap for order lookup
- Uses CoralPool for zero-GC

**Use Case:** Order book data structure and matching logic  
**Latency:** Sub-microsecond (in-memory)  
**Phase:** Reference for all phases

---

## Technology Comparison

| **Aspect** | **Disruptor** | **Aeron** | **CoralME** |
|------------|---------------|-----------|-------------|
| **Purpose** | Inter-thread | Inter-process/network | Order matching |
| **Scope** | Single JVM | IPC + Network | Library/DS |
| **Latency** | Sub-μs | μs (IPC), ms (net) | Sub-μs |
| **GC-Free** | ✅ | ✅ | ✅ |
| **Persistence** | ❌ | ✅ (Archive) | ❌ |
| **Fault Tolerance** | ❌ | ✅ (Cluster) | ❌ |
| **Multi-node** | ❌ | ✅ | ❌ |

**Integration Strategy:**
- **Phase 1 (MVP):** Disruptor for messaging + CoralME patterns for orderbook
- **Phase 2 (Standard):** Aeron (IPC/network) + Aeron Archive (persistence) + Aeron Cluster (HA)
- **Phase 3 (Advanced):** Coral Blocks C++ (CoralRing) + keep Aeron Cluster if needed

---

## MVP Architecture Sketch

---

## 3-Version Implementation Strategy

**Goal:** Build incremental versions to compare latency performance across different messaging technologies

**Test Scenario:** Market Data (Recombinor) → OSM flow
- Simplified flow focusing on critical path
- Measure end-to-end latency from Recombinor publish to OSM receive
- All versions use same OrderBook implementation (CoralME patterns)

---

### Version 1: LMAX Disruptor (Intra-Process)

**Architecture:**
```
Single JVM Process:
  Thread-1 (Recombinor) → Disruptor RingBuffer → Thread-2 (OSM)
```

**Implementation Details:**

| **Component** | **Implementation** |
|---------------|-------------------|
| **Messaging** | LMAX Disruptor RingBuffer |
| **Process Model** | Single JVM, multi-threaded |
| **Event Type** | `BookUpdateEvent` (pre-allocated) |
| **Ring Size** | 2048 (power of 2) |
| **Wait Strategy** | `BusySpinWaitStrategy` |
| **Sequencer** | `SingleProducerSequencer` |
| **Threading** | Dedicated threads with CPU affinity |

**Code Structure:**
```
common/
  ├── events/
  │   ├── BookUpdateEvent.java         (Disruptor event)
  │   └── EventFactory implementations
  └── ...

recombinor/
  ├── DisruptorRecombinor.java         (Producer)
  └── ...

osm/
  ├── DisruptorOSMHandler.java         (EventHandler)
  ├── OrderBook.java                    (CoralME pattern)
  └── ...

app/
  └── DisruptorApp.java                 (Main launcher)
```

**Key Code:**
```java
// Disruptor setup
Disruptor<BookUpdateEvent> disruptor = new Disruptor<>(
    BookUpdateEvent::new,
    2048,
    DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE,
    new BusySpinWaitStrategy()
);

// OSM as EventHandler
disruptor.handleEventsWith(osmHandler);
disruptor.start();

// Recombinor publishes
RingBuffer<BookUpdateEvent> ringBuffer = disruptor.getRingBuffer();
long seq = ringBuffer.next();
BookUpdateEvent event = ringBuffer.get(seq);
event.setData(...);
ringBuffer.publish(seq);
```

**Latency Target:** 50-150ns (Recombinor → OSM)

**Pros:**
- ✅ Simplest implementation
- ✅ Lowest latency (same process)
- ✅ Easy to debug
- ✅ No file management

**Cons:**
- ❌ Single point of failure (entire process crashes)
- ❌ No process isolation
- ❌ Cannot scale across machines

**Estimated Development:** 3-5 days

---

### Version 2: Aeron IPC + Shared Memory (Zero-Copy) - IMPLEMENTED

**Status:** ✅ Prototype Complete (Dec 9, 2025)

**Architecture (Zero-Copy Pattern):**
```
MDR Process:
  1. Write MarketData payload to /dev/shm (MappedByteBuffer)
  2. Send tiny reference message (29 bytes) via Aeron IPC
     - Contains: slotIndex, slotVersion, timestamp, sequenceId
  
MM Process:
  3. Subscribe to Aeron IPC (receive reference messages)
  4. Read payload directly from shared memory (zero-copy)
  5. Process market data
```

**Key Components:**

1. **SharedMemoryRingBuffer** (`common/shm/`)
   - Fixed-size slots (128 bytes each, 4096 slots = 512 KB)
   - Ring buffer pattern (circular, modulo via bitwise AND)
   - Version-based optimistic locking (detect overwrites)
   - Layout per slot: [version|eventType|payloadSize|payload]
   - Uses Agrona UnsafeBuffer for performance

2. **MarketDataReference** (`common/events/aeron/`)
   - Tiny 29-byte message sent via Aeron
   - Contains: sequenceId, timestamp, slotIndex, slotVersion, eventType
   - Fits in single CPU cache line

3. **MarketDataPayload** (`common/events/aeron/`)
   - 88-byte fixed-size payload in shared memory
   - Contains: symbol, bidPrice, askPrice, bidSize, askSize, timestamp, seq, bookState
   - Encoded/decoded with Agrona DirectBuffer

4. **AeronRecombinor** (`recombinor/aeron/`)
   - Producer: Writes to shared memory + publishes to Aeron
   - Embedded Media Driver
   - Generates synthetic market data for testing

5. **AeronSignalHandler** (`signal/aeron/`)
   - Consumer: Subscribes to Aeron + reads from shared memory
   - Zero-copy: Direct memory access, no serialization
   - HDR Histogram for latency measurement

6. **AeronSharedMemoryApp** (`app/aeron/`)
   - Integration test: MDR → MM flow
   - Warmup + measurement phases
   - Latency statistics

**Expected Performance:**
- Latency: 2-5 μs (MDR → MM)
- Throughput: > 5M msgs/sec
- Zero GC (no allocations in hot path)

---

### Version 2: Aeron Cluster (Global Sequencer) - SIMPLIFIED & CORRECTED

**Status:** ✅ Implementation Complete (Dec 9, 2025)

**Architecture (Simplified - Correct Pattern):**
```
MDR Process:
  1. Write full payload to /dev/shm (bestBid, bestAsk, timestamp, venueId)
  2. Send tiny reference (4 bytes: ID only) to Cluster Ingress
  
Aeron Cluster (3 nodes):
  3. Leader receives reference, sequences it (Raft log ordering)
  4. NO data manipulation - just forwards reference
  5. Broadcasts to all consumers via Egress
  6. Followers automatically replay same ordered log
  
MM/OSM Processes:
  7. Receive globally ordered reference (4 bytes) from Cluster Egress
  8. Read full payload directly from shared memory (zero-copy!)
  9. Process in deterministic order
```

**Key Components:**

1. **SharedMemoryStore** (`common/shm/`)
   - Simple fixed-entry store (32 bytes per entry)
   - Direct addressing: id * ENTRY_SIZE
   - No versioning, no headers - just raw data
   - writeEntry(id, bid, ask, ts, venue)
   - readEntry(id) → MarketData

2. **MdRefMessage** (`common/events/aeron/`)
   - Tiny 4-byte message (just entry ID)
   - Minimal overhead through cluster
   - encode(msg, buffer) / decode(buffer)

3. **SequencerService** (`common/cluster/`)
   - ClusteredService implementation
   - Receives MD ref on ingress
   - **Does NOT manipulate data**
   - Just forwards to egress (cluster handles replication)
   - Stateless (no sequence counter needed)

4. **MDRProcess** (`recombinor/cluster/`)
   - Writes full payload to shared memory
   - Sends 4-byte reference to cluster
   - Simple and clean

5. **MMProcess** (`signal/cluster/`)
   - Subscribes to cluster egress (EgressListener)
   - Receives 4-byte reference
   - Reads from shared memory (zero-copy)
   - HDR latency measurement

**Key Insights:**
- ✅ **Sequencer does NOT read shared memory**
- ✅ **Sequencer only orders the reference (MD_ID)**
- ✅ **Followers automatically replay same ordered log**
- ✅ **Zero serialization, zero copying**
- ✅ **Deterministic ordering via cluster log**

**Benefits:**
- ✅ **True Global Sequencer**: Raft consensus, total ordering
- ✅ **Minimal Overhead**: Only 4 bytes through consensus
- ✅ **Zero-Copy**: Payloads in shared memory
- ✅ **Simple**: No data manipulation in sequencer
- ✅ **Deterministic**: Cluster log guarantees order

**Trade-offs:**
- ⚠️ **Latency**: 6-11 μs (Raft consensus)
- ⚠️ **Throughput**: ~100K msgs/sec sustained
- ⚠️ **Single Machine**: Shared memory = same host only

**Expected Performance:**
- Latency: 6-11 μs (includes Raft consensus)
- Throughput: 100K msgs/sec sustained
- Message Size: 4 bytes through cluster (vs 88+ bytes)

---

### Version 2: Aeron IPC (Inter-Process, Shared Memory) - ORIGINAL PLAN

**Architecture:**
```
Process-1 (Recombinor JVM):
  Aeron Publisher → /dev/shm/aeron (mmap)

Process-2 (OSM JVM):
  Aeron Subscriber ← /dev/shm/aeron (mmap)
  
Media Driver (Embedded or Standalone):
  Manages shared memory log buffers
```

**Implementation Details:**

| **Component** | **Implementation** |
|---------------|-------------------|
| **Messaging** | Aeron IPC (memory-mapped files) |
| **Process Model** | Multi-process (2 JVMs) |
| **Event Type** | `BookUpdateEvent` (SBE-encoded) |
| **Transport** | `aeron:ipc` (shared memory) |
| **Media Driver** | Embedded (for simplicity) or C driver (for performance) |
| **Buffer Size** | 2MB log buffer, 16MB term buffer |
| **Stream ID** | 1001 (Recombinor → OSM) |
| **Threading** | Aeron manages threads |

**Code Structure:**
```
common/
  ├── sbe/
  │   ├── BookUpdateSchema.xml          (SBE schema)
  │   └── generated/                    (SBE codegen)
  └── ...

recombinor/
  ├── AeronRecombinorPublisher.java     (Aeron Publication)
  └── ...

osm/
  ├── AeronOSMSubscriber.java           (Aeron Subscription)
  ├── OrderBook.java                    (Same as V1)
  └── ...

app/
  ├── AeronRecombinorApp.java           (Process 1)
  └── AeronOSMApp.java                  (Process 2)
```

**Key Code:**
```java
// Process 1: Recombinor Publisher
MediaDriver driver = MediaDriver.launch();
Aeron aeron = Aeron.connect();
Publication publication = aeron.addPublication(
    "aeron:ipc",
    1001
);

// Publish with SBE encoding
UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
BookUpdateEncoder encoder = new BookUpdateEncoder();
encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
encoder.symbol("AAPL");
encoder.bidPrice(15000);
encoder.askPrice(15100);
publication.offer(buffer, 0, encoder.encodedLength());

// Process 2: OSM Subscriber
Subscription subscription = aeron.addSubscription(
    "aeron:ipc",
    1001
);

FragmentHandler handler = (buffer, offset, length, header) -> {
    BookUpdateDecoder decoder = new BookUpdateDecoder();
    decoder.wrap(buffer, offset, 0, 0);
    orderBook.updateMarketData(decoder);
};

while (running) {
    subscription.poll(handler, 10);
}
```

**Configuration:**
```properties
# aeron.properties
aeron.dir=/dev/shm/aeron
aeron.threading.mode=SHARED
aeron.ipc.term.buffer.length=16m
```

**Latency Target:** 150-400ns (Recombinor → OSM)

**Pros:**
- ✅ Process isolation (fault containment)
- ✅ Can use C Media Driver (better performance)
- ✅ Path to clustering (Aeron Cluster)
- ✅ Professional, production-ready

**Cons:**
- ❌ More complex setup (Media Driver)
- ❌ SBE encoding overhead
- ❌ File management (/dev/shm/)

**Estimated Development:** 7-10 days (includes SBE schema setup)

---

### Version 3: CoralRing (Inter-Process, Shared Memory)

**Architecture:**
```
Process-1 (Recombinor JVM):
  CoralRing RingProducer → /dev/shm/coralring.mmap

Process-2 (OSM JVM):
  CoralRing RingConsumer ← /dev/shm/coralring.mmap
  
Direct memory-mapped file, no Media Driver
```

**Implementation Details:**

| **Component** | **Implementation** |
|---------------|-------------------|
| **Messaging** | CoralRing WaitingRing (or NonWaitingRing) |
| **Process Model** | Multi-process (2 JVMs) |
| **Event Type** | Custom mutable transfer object |
| **Transport** | Memory-mapped file (/dev/shm/) |
| **Ring Size** | 2048 slots |
| **Mode** | WaitingRing (backpressure) |
| **Memory** | Unsafe + mmap (as per SharedMemory.java) |

**Code Structure:**
```
common/
  ├── coral/
  │   ├── BookUpdateMutable.java        (CoralRing transfer object)
  │   └── Mutable interface impl
  └── ...

recombinor/
  ├── CoralRingRecombinorProducer.java  (RingProducer)
  └── ...

osm/
  ├── CoralRingOSMConsumer.java         (RingConsumer)
  ├── OrderBook.java                    (Same as V1)
  └── ...

app/
  ├── CoralRingRecombinorApp.java       (Process 1)
  └── CoralRingOSMApp.java              (Process 2)
```

**Key Code:**
```java
// Define mutable transfer object
public class BookUpdateMutable implements Mutable {
    private CharSequence symbol;
    private long bidPrice;
    private long askPrice;
    private long bidSize;
    private long askSize;
    
    public static int getMaxSize() {
        return 64; // bytes
    }
    
    @Override
    public void writeTo(Memory memory, long address) {
        // Serialize to memory
    }
    
    @Override
    public void readFrom(Memory memory, long address) {
        // Deserialize from memory
    }
}

// Process 1: Recombinor Producer
RingProducer<BookUpdateMutable> producer = new WaitingRingProducer<>(
    BookUpdateMutable.getMaxSize(),
    BookUpdateMutable.class,
    "/dev/shm/coralring-recombinor-osm.mmap",
    2048
);

// Publish
BookUpdateMutable msg = producer.nextToDispatch();
msg.setSymbol("AAPL");
msg.setBidPrice(15000);
msg.setAskPrice(15100);
producer.flush();

// Process 2: OSM Consumer
RingConsumer<BookUpdateMutable> consumer = new WaitingRingConsumer<>(
    BookUpdateMutable.getMaxSize(),
    BookUpdateMutable.class,
    "/dev/shm/coralring-recombinor-osm.mmap"
);

// Consume
while (running) {
    long avail = consumer.availableToFetch();
    if (avail == 0) continue; // busy spin
    
    for (long i = 0; i < avail; i++) {
        BookUpdateMutable msg = consumer.fetch();
        orderBook.updateMarketData(msg);
    }
    consumer.doneFetching();
}
```

**Latency Target:** 200-500ns (Recombinor → OSM)

**Pros:**
- ✅ Simple setup (no Media Driver)
- ✅ Direct memory access (Unsafe)
- ✅ Automatic persistence (mmap file)
- ✅ NonWaiting mode option (consumer can lag)

**Cons:**
- ❌ Java-only (no C++ clients)
- ❌ Manual mmap file management
- ❌ Uses deprecated Unsafe API
- ❌ Higher latency than Disruptor/Aeron
- ❌ Both processes still have GC pauses

**Estimated Development:** 5-7 days

---

## Performance Comparison Plan

**Benchmark Methodology:**

1. **Warmup Phase:**
   - Send 100,000 messages to warm up JIT
   - Discard measurements

2. **Measurement Phase:**
   - Send 1,000,000 messages
   - Record timestamp at Recombinor publish
   - Record timestamp at OSM receive
   - Calculate latency = receive_time - publish_time

3. **Metrics to Capture:**
   - Minimum latency (best case)
   - Average latency (mean)
   - Median latency (P50)
   - P99 latency (99th percentile)
   - P99.9 latency (99.9th percentile)
   - Maximum latency (worst case)
   - Throughput (messages/sec)

4. **Test Scenarios:**
   - Scenario A: Idle system (no load)
   - Scenario B: Under load (10M msgs/sec)
   - Scenario C: With GC stress (force GC cycles)

**Expected Results:**

| **Version** | **Min (ns)** | **Avg (ns)** | **P99 (ns)** | **P99.9 (ns)** | **Throughput** |
|-------------|--------------|--------------|--------------|----------------|----------------|
| **V1: Disruptor** | 50 | 100 | 300 | 1,000 | 20M msgs/s |
| **V2: Aeron IPC** | 150 | 300 | 800 | 2,000 | 15M msgs/s |
| **V3: CoralRing** | 200 | 400 | 1,200 | 3,000 | 10M msgs/s |

**Tools:**
- HdrHistogram for latency percentiles
- JMH for microbenchmarks
- JFR (Java Flight Recorder) for profiling
- Linux `perf` for CPU profiling

---

## Implementation Roadmap

**Week 1-2: Version 1 (Disruptor)**
- ✅ Day 1-2: Set up Disruptor dependency, create BookUpdateEvent
- ✅ Day 3-4: Implement Recombinor producer + OSM consumer
- ✅ Day 5-6: Add latency measurement, run benchmarks
- ✅ Day 7: Document results, create baseline

**Week 3-4: Version 2 (Aeron IPC)**
- ✅ Day 8-9: Set up Aeron dependency, design SBE schema
- ✅ Day 10-11: Generate SBE codecs, implement Aeron publisher
- ✅ Day 12-13: Implement Aeron subscriber, integrate OSM
- ✅ Day 14-15: Configure Media Driver (embedded + C driver tests)
- ✅ Day 16-17: Run benchmarks, compare with V1
- ✅ Day 18: Document results

**Week 5-6: Version 3 (CoralRing)**
- ✅ Day 19-20: Set up CoralRing dependency, create Mutable transfer object
- ✅ Day 21-22: Implement RingProducer (Recombinor)
- ✅ Day 23-24: Implement RingConsumer (OSM)
- ✅ Day 25-26: Handle mmap file lifecycle, error handling
- ✅ Day 27-28: Run benchmarks, compare with V1 & V2
- ✅ Day 29: Document results

**Week 7: Analysis & Decision**
- ✅ Day 30-31: Analyze all 3 versions
- ✅ Day 32: Create comparison report
- ✅ Day 33: Choose final architecture for full system
- ✅ Day 34: Present findings, get approval
- ✅ Day 35: Plan next phase (full tick-to-trade implementation)

---

## Simplified Flow (All Versions)

**Assumption:** Focus on Recombinor → OSM path only

```
MARKET DATA SIMULATION (all versions):
  - Recombinor generates synthetic BookUpdateEvents
  - Simulates CME market data (no actual UDP)
  - Publishes at configurable rate (e.g., 1M msgs/sec)

OSM BEHAVIOR (all versions):
  - Receives BookUpdateEvent
  - Updates internal reference book (simple state tracking)
  - Records latency metrics
  - No actual matching (Phase 2 feature)

EXCLUDED FROM MVP VERSIONS:
  ❌ Gateway module (no real UDP)
  ❌ Signal module (no strategy logic)
  ❌ Liquidator module (no external connectivity)
  ❌ Full orderbook matching (just state updates)
```

---

## Gradle Dependencies

**Version 1 (Disruptor):**
```gradle
dependencies {
    implementation 'com.lmax:disruptor:4.0.0'
    implementation 'org.hdrhistogram:HdrHistogram:2.1.12'
}
```

**Version 2 (Aeron):**
```gradle
dependencies {
    implementation 'io.aeron:aeron-all:1.44.1'
    implementation 'uk.co.real-logic:sbe-all:1.30.0'
    implementation 'org.hdrhistogram:HdrHistogram:2.1.12'
}
```

**Version 3 (CoralRing):**
```gradle
dependencies {
    implementation 'com.github.coralblocks:CoralRing:1.15.2'
    implementation 'com.github.coralblocks:CoralPool:1.4.1'
    implementation 'org.hdrhistogram:HdrHistogram:2.1.12'
}

repositories {
    maven { url 'https://jitpack.io' }
}
```

---

## Success Criteria

**Version 1 (Disruptor):**
- ✅ Achieves <200ns average latency
- ✅ Handles 10M+ msgs/sec
- ✅ Zero GC during measurement phase
- ✅ Clean code, well-documented

**Version 2 (Aeron IPC):**
- ✅ Achieves <500ns average latency
- ✅ Handles 5M+ msgs/sec
- ✅ Demonstrates process isolation
- ✅ Works with both embedded and C Media Driver

**Version 3 (CoralRing):**
- ✅ Achieves <600ns average latency
- ✅ Handles 3M+ msgs/sec
- ✅ Demonstrates mmap persistence
- ✅ Clean file lifecycle management

**Decision Criteria:**
- If latency delta < 100ns: Choose Aeron (better for Phase 2 scaling)
- If latency delta > 300ns: Stay with Disruptor (optimize single-process first)
- CoralRing unlikely winner (unless specific mmap persistence requirement)

---

## Next Steps

1. ✅ **Create Version 1 (Disruptor)** - Start with simplest implementation
2. ✅ Establish baseline latency metrics
3. ✅ Implement Version 2 (Aeron IPC) - Compare process isolation cost
4. ✅ Implement Version 3 (CoralRing) - Compare mmap overhead
5. ✅ Analyze results, make architecture decision
6. ✅ Proceed with full tick-to-trade implementation using chosen technology

---

**END OF SESSION PLAN**
