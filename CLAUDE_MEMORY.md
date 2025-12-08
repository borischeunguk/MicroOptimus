# Claude Session Memory - MicroOptimus Project

**Last Updated:** December 8, 2025  
**Session:** Implementation Planning - 3 Version Strategy

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

## Progress Summary

✅ Reviewed project objectives  
✅ Cloned reference repos: LMAX Disruptor, Aeron, CoralME, CoralPool, CoralQueue, CoralRing  
✅ Analyzed LMAX Disruptor architecture  
✅ Analyzed CoralME matching engine  
✅ Analyzed Aeron messaging system  
✅ Compared all three technologies

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

### Version 2: Aeron IPC (Inter-Process, Shared Memory)

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
