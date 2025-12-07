# OSM (Order State Manager) Module

## Overview

The OSM module implements a high-performance, GC-free orderbook and matching engine using **CoralME design patterns**. It provides sub-microsecond latency for order matching using object pooling and intrusive linked lists.

## Architecture

### Key Components

1. **OrderBook** - Main orderbook with price-time priority matching
2. **MatchingEngine** - Processes orders and manages matching logic
3. **OrderStateManager** - Coordinates internal matching vs CME routing
4. **Order** - Pooled order objects with intrusive list pointers
5. **PriceLevel** - Pooled price level objects with intrusive list pointers

### Design Patterns (from CoralME)

#### 1. Object Pooling for Zero GC
```java
private final ObjectPool<Order> orderPool;
private final ObjectPool<PriceLevel> priceLevelPool;

// Get from pool
Order order = orderPool.get();
order.init(...);  // Initialize with values

// Return to pool
order.reset();     // Clear fields
orderPool.release(order);
```

**Benefits:**
- Zero garbage collection during operation
- Pre-allocated objects for spatial locality
- Consistent memory layout for cache efficiency

#### 2. Intrusive Linked Lists
```java
public class Order {
    // Data fields
    private long orderId;
    private long price;
    
    // Intrusive pointers (no external Node wrapper)
    Order next = null;
    Order prev = null;
}
```

**Benefits:**
- 50% fewer memory accesses (no Node → Order indirection)
- Better cache utilization
- Reduced pointer chasing

#### 3. Primitive Long for Prices
```java
private long price;  // Stored as ticks, not double
```

**Benefits:**
- No floating-point precision errors
- Faster integer arithmetic
- Deterministic exact matching

#### 4. Fast O(1) Order Lookup
```java
private final LongMap<Order> orders;  // Custom hash map for long keys
```

**Benefits:**
- No boxing of long keys
- Optimized for primitive long lookups
- Cache-friendly open addressing

## Data Structure Design

### Price Level Lists
```
head[0] (BUY)  → [100.00: Order1→Order2] → [99.50: Order3] → tail[0]
head[1] (SELL) → [100.50: Order4] → [101.00: Order5→Order6] → tail[1]
```

- Doubly-linked price levels sorted by price
- Each price level contains doubly-linked orders (time priority)
- head[0] = best bid, head[1] = best ask

### Memory Layout (After Warmup)
```
Order Pool:      [Order1][Order2][Order3]...[Order128]     ← Sequential
PriceLevel Pool: [PL1][PL2][PL3]...[PL64]                  ← Sequential
Active Book:     PL1 → Order1 → Order2 → Order3
                  ↓
                 PL2 → Order4 → Order5
                  
All from pre-allocated pools for cache locality!
```

## Performance Characteristics

### Complexity
- **Best bid/ask lookup**: O(1)
- **Order lookup by ID**: O(1) via LongMap
- **Top-of-book match**: O(1)
- **Mid-book match**: O(P + O) where P = price levels, O = orders per level
- **Add order**: O(P) for price level insertion, O(1) for order insertion

### Expected Throughput
- **Top-of-book matches**: 2-5 million orders/sec
- **Mid-book operations**: 1-3 million orders/sec
- **Mixed workload**: 1-2 million orders/sec

### GC-Free Operation
Run tests with `-verbose:gc -Xms256m -Xmx256m` to verify zero GC:
```bash
./gradlew :osm:jmh -Pjmh.jvmArgs="-verbose:gc -Xms256m -Xmx256m"
```

## Configuration

### Tunable Pool Sizes
```java
// Adjust based on expected workload
OrderBook.ORDER_POOL_INITIAL_SIZE = 128;        // For high order count
OrderBook.PRICE_LEVEL_POOL_INITIAL_SIZE = 64;   // For deep books
```

### Trade-to-Self Prevention
```java
OrderBook book = new OrderBook("AAPL", false);  // Disable trade-to-self
```

## Usage Examples

### Basic Order Matching
```java
OrderBook book = new OrderBook("AAPL");

// Add resting sell order
book.addLimitOrder(1L, 100L, Side.SELL, 15000L, 100L, TimeInForce.DAY);

// Add aggressive buy order (matches immediately)
Order order = book.addLimitOrder(2L, 101L, Side.BUY, 15000L, 100L, TimeInForce.IOC);

assertTrue(order.isFilled());
```

### Market Making
```java
OrderStateManager osm = new OrderStateManager("AAPL");

// Add bid quote
osm.processOrder(1L, 100L, Side.BUY, OrderType.LIMIT, 
                 14950L, 100L, TimeInForce.GTC);

// Add ask quote
osm.processOrder(2L, 100L, Side.SELL, OrderType.LIMIT, 
                 15050L, 100L, TimeInForce.GTC);
```

### Cancel Order
```java
boolean cancelled = book.cancelOrder(orderId);
```

## Integration with Disruptor

The OrderBook is designed to work with LMAX Disruptor for inter-component messaging:

```java
// In Disruptor event handler
public void onEvent(OrderRequest event, long sequence, boolean endOfBatch) {
    // Process order through OSM
    OrderResult result = osm.processOrder(
        event.getOrderId(),
        event.getClientId(),
        event.getSide(),
        event.getOrderType(),
        event.getPrice(),
        event.getQuantity(),
        event.getTif()
    );
    
    // Publish result to next RingBuffer
    if (result == OrderResult.ROUTE_TO_CME) {
        publishToExternalOrderRing(event);
    } else if (result == OrderResult.FILLED_INTERNALLY) {
        publishToExecutionRing(event);
    }
}
```

## Testing

### Unit Tests
```bash
./gradlew :osm:test
```

### JMH Benchmarks
```bash
# Run all benchmarks
./gradlew :osm:jmh

# Run specific benchmark
./gradlew :osm:jmh -Pjmh.includes='.*OrderBook.*'
```

### GC-Free Verification
```bash
# Should show ZERO GC activity
./gradlew :osm:jmh -Pjmh.includes='.*NoGC.*' \
  -Pjmh.jvmArgs="-verbose:gc -Xms256m -Xmx256m"
```

## Key Differences from Traditional OrderBook

| Aspect | Traditional | CoralME-Based (Ours) |
|--------|------------|----------------------|
| **Memory** | New objects per order | Pre-allocated pools |
| **GC** | Frequent GC pauses | Zero GC |
| **Cache** | Scattered in heap | Sequential in pool |
| **Lookup** | HashMap<Long, Order> | LongMap (no boxing) |
| **Links** | External Node objects | Intrusive pointers |
| **Price** | double (imprecise) | long ticks (exact) |
| **Throughput** | 500K-1M orders/sec | 1-5M orders/sec |

## Performance Tips

1. **Warm up pools** - Pre-allocate before benchmarking
2. **Pin threads** - Use thread affinity for consistent performance
3. **Size pools correctly** - Match expected workload
4. **Disable GC** - Use fixed heap size for testing
5. **Profile hot paths** - Top-of-book operations are most critical

## References

- CoralME: `/CoralME/` - Reference implementation
- CoralPool: `/CoralPool/` - Object pooling library
- LMAX Disruptor: `/disruptor/` - Event transport
- CLAUDE_MEMORY.md - Project architecture and requirements

## License

Copyright (c) 2025 MicroOptimus Project

