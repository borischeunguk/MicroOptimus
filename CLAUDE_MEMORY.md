# Claude Session Memory - MicroOptimus Project

**Last Updated:** December 6, 2025  
**Session:** Initial Planning - Reference Repository Analysis

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
