# Aeron Cluster Global Sequencer Architecture

**Date:** December 9, 2025  
**Version:** MicroOptimus V2 - True Global Sequencer

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Shared Memory (/dev/shm)                     │
│              Ring Buffer: 4096 slots × 128 bytes                │
│         [Market Data Payloads - Zero Copy Access]              │
└─────────────────────────────────────────────────────────────────┘
         ▲ write                     ▲ read        ▲ read
         │                           │             │
    ┌────┴────┐                 ┌────┴────┐  ┌────┴────┐
    │   MDR   │                 │   MM    │  │   OSM   │
    │ Process │                 │ Process │  │ Process │
    └────┬────┘                 └────▲────┘  └────▲────┘
         │                           │             │
         │ ingress                   │ egress      │ egress
         │ (MD_REF)                  │ (MD_REF)    │ (MD_REF)
         │                           │             │
         │                           │             │
         └───────────────┬───────────┴─────────────┘
                         │
                ┌────────▼─────────┐
                │  Aeron Cluster   │
                │   (3 nodes)      │
                │                  │
                │  ┌────────────┐  │
                │  │   Leader   │  │ ← Global Sequencer
                │  │ (Clustered │  │   Total ordering
                │  │  Service)  │  │   Consensus
                │  └────────────┘  │
                │                  │
                │  ┌──────┐ ┌──────┐
                │  │Fllwr1│ │Fllwr2│ ← Replicated log
                │  └──────┘ └──────┘   Deterministic
                └──────────────────────┘
```

---

## Key Components

### 1. **Aeron Cluster (Global Sequencer)**

**Role:** Provides total ordering of all events
- **Leader**: Accepts ingress messages, sequences them, applies to state machine
- **Followers**: Replicate log, can take over on leader failure
- **Consensus**: Raft-based (requires quorum for commits)

**Latency:** 5-10 μs (consensus overhead)

**Messages Through Cluster:**
- **Ingress**: MDR → Cluster (MarketDataReference)
- **Egress**: Cluster → MM/OSM (Sequenced MarketDataReference)

### 2. **Shared Memory (Zero-Copy Payloads)**

**Role:** Store actual market data payloads
- MDR writes payload to shared memory slot
- Gets back slotIndex + version
- Sends tiny reference (29 bytes) to cluster
- MM/OSM receive reference from cluster egress
- Read payload directly from shared memory (zero-copy)

### 3. **ClusteredService (State Machine)**

**Role:** Application logic running in cluster
- Receives MarketDataReference from ingress
- Validates + sequences
- Broadcasts to egress (all consumers)
- Maintains sequence numbers (deterministic)

---

## Message Flow

### Step 1: MDR Writes to Shared Memory
```java
// 1. Encode market data payload
byte[] payload = MarketDataPayload.encode(...);

// 2. Write to shared memory
SlotDescriptor slot = sharedMemory.write(payload);
// Returns: slotIndex=42, version=137
```

### Step 2: MDR Sends Reference to Cluster Ingress
```java
// 3. Create reference message (29 bytes)
MarketDataReference ref = new MarketDataReference(
    0,                  // sequenceId (filled by cluster)
    System.nanoTime(),  // timestamp
    slot.slotIndex,     // 42
    slot.version,       // 137
    EVENT_TYPE_MD
);

// 4. Send to cluster ingress
clusterIngressSession.offer(ref.encode());
```

### Step 3: Cluster Leader Sequences
```java
// ClusteredService.onSessionMessage()
public void onSessionMessage(
    ClientSession session,
    long timestamp,
    DirectBuffer buffer,
    int offset,
    int length,
    Header header) {
    
    // Decode reference
    MarketDataReference.Decoder ref = decode(buffer, offset);
    
    // Assign global sequence number
    long globalSeq = nextSequence++;
    
    // Update reference with sequence
    ref.setSequenceId(globalSeq);
    
    // Broadcast to all consumers via egress
    for (ClientSession consumer : sessions) {
        consumer.offer(ref.encode());
    }
    
    // Store in replicated log (automatic)
}
```

### Step 4: MM/OSM Receive from Cluster Egress
```java
// MM Process
clusterEgressListener.onMessage(buffer, offset, length) {
    // Decode sequenced reference
    MarketDataReference.Decoder ref = decode(buffer, offset);
    
    long receiveTime = System.nanoTime();
    long latency = receiveTime - ref.timestamp();
    
    // Read payload from shared memory (zero-copy)
    SlotDescriptor slot = new SlotDescriptor(
        ref.slotIndex(), ref.version()
    );
    
    byte[] payload = new byte[128];
    int bytesRead = sharedMemory.read(slot, payload);
    
    // Process market data
    MarketDataPayload.Decoder md = decode(payload);
    processMarketData(md);
}
```

---

## Benefits of This Architecture

### ✅ **True Global Sequencer**
- All events have strict total order (via Raft consensus)
- Sequence numbers assigned by cluster leader
- Deterministic replay from replicated log

### ✅ **High Availability**
- 3-node cluster (survives 1 failure)
- Automatic leader election
- No single point of failure

### ✅ **Zero-Copy Payloads**
- Market data stays in shared memory
- Only tiny references (29 bytes) go through cluster
- Minimal consensus overhead

### ✅ **Audit Trail**
- Complete event log in cluster
- Can replay entire system state
- Compliance ready

### ✅ **Scalability**
- Add more consumers (subscribe to egress)
- All consumers see same sequence
- Fan-out pattern

---

## Trade-offs

### ⚠️ **Higher Latency**
- Consensus overhead: 5-10 μs
- Vs Aeron IPC: ~1 μs
- **Total: 6-11 μs** (vs 950 ns target)

### ⚠️ **Complexity**
- Cluster setup (3 nodes)
- State machine implementation
- Network configuration

### ⚠️ **Throughput**
- Limited by consensus (~100K msgs/sec sustained)
- Vs Aeron IPC: 5M+ msgs/sec
- Need batching for high throughput

---

## Implementation Plan

### Phase 1: ClusteredService
1. **MarketDataSequencerService** - State machine
   - onSessionMessage() - receive MD refs from MDR
   - Assign sequence numbers
   - Broadcast to egress

2. **Cluster Configuration**
   - 3 nodes (ports, directories)
   - Consensus module settings
   - Archive settings

### Phase 2: MDR Integration
1. **ClusterIngressPublisher**
   - Connect to cluster ingress
   - Send MD references (not full payloads)
   - Handle back-pressure

### Phase 3: MM/OSM Integration
1. **ClusterEgressSubscriber**
   - Connect to cluster egress
   - Receive sequenced references
   - Read from shared memory

### Phase 4: Testing
1. Single-node cluster (development)
2. 3-node cluster (HA testing)
3. Failure scenarios (leader election)
4. Latency measurement

---

## Expected Performance

### Latency Breakdown

| Stage | Operation | Latency |
|-------|-----------|---------|
| 1 | MDR: Write to shared memory | 100 ns |
| 2 | MDR: Encode reference | 20 ns |
| 3 | MDR: Send to cluster ingress | 200 ns |
| 4 | **Cluster: Consensus** | **5-10 μs** |
| 5 | Cluster: Broadcast egress | 200 ns |
| 6 | MM: Receive from egress | 200 ns |
| 7 | MM: Read shared memory | 100 ns |
| **Total** | **MDR → MM** | **6-11 μs** |

### Throughput

- **With Consensus**: ~100K msgs/sec (sustained)
- **With Batching**: ~500K msgs/sec (bursts)

### Comparison

| Architecture | Latency | Throughput | HA | Audit |
|--------------|---------|------------|----|----|
| V1 (Disruptor) | < 2 μs | 10M/s | ❌ | ❌ |
| V2 (Aeron IPC) | < 1 μs | 5M/s | ❌ | ❌ |
| **V2 (Cluster)** | **6-11 μs** | **100K/s** | ✅ | ✅ |

---

## Files to Create

1. **MarketDataSequencerService.java** - ClusteredService implementation
2. **ClusterIngressPublisher.java** - MDR publishes to cluster
3. **ClusterEgressSubscriber.java** - MM/OSM subscribe from cluster
4. **AeronClusterNode.java** - Cluster node launcher
5. **AeronClusterApp.java** - Full integration test

---

## Conclusion

This is the **true global sequencer architecture**:
- Aeron Cluster provides total ordering via Raft consensus
- Shared memory provides zero-copy payloads
- Trade latency (6-11 μs) for HA + audit trail

**Use Cases:**
- ✅ Production systems requiring HA
- ✅ Compliance/audit requirements
- ✅ Multi-consumer fan-out
- ❌ Sub-microsecond tick-to-trade (too slow)

**Next:** Implement ClusteredService + integration

