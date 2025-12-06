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

**Why Good for MVP:**
- Single-process ✓
- Sub-microsecond latency ✓
- Swap to Aeron later ✓

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

**Why Relevant:**
- Production-ready reference for GC-free order book ✓
- Clean callback API design ✓
- Shows CoralBlocks ecosystem patterns ✓
- Can integrate with Disruptor ✓

---

## MVP Architecture Sketch
