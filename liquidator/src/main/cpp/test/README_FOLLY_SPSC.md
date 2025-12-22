# Folly ProducerConsumerQueue (SPSC) Unit Tests

## Overview

Comprehensive unit tests for Folly's `ProducerConsumerQueue` - a lock-free, wait-free Single Producer Single Consumer (SPSC) queue optimized for ultra-low latency inter-thread communication.

## Test Coverage

### Functional Tests (18 tests)

1. **ConstructionAndCapacity** - Basic queue initialization
2. **WriteAndRead** - Element insertion and retrieval
3. **FIFOOrdering** - FIFO order verification
4. **FullQueueBehavior** - Full queue handling
5. **EmptyQueueBehavior** - Empty queue handling
6. **FrontPtrZeroCopy** - Zero-copy read access
7. **CustomStruct** - User-defined types (Order struct)
8. **PointerTypes** - Pointer storage for zero-copy patterns
9. **WrapAround** - Circular buffer wrap-around
10. **ProducerConsumerSimulation** - Single-thread simulation
11. **MultiThreadedSPSC** - Actual multi-threaded SPSC
12. **PerformanceSingleThreaded** - Single-thread throughput
13. **PerformanceMultiThreaded** - Multi-thread throughput
14. **StringTypes** - Non-trivial type handling
15. **LargeCapacity** - Large queue capacity
16. **QueueReuse** - Multiple write/read cycles

## Why Folly SPSC for Trading Systems?

### Key Advantages

1. **Lock-Free** - No mutex contention, pure atomic operations
2. **Wait-Free** - Bounded time for all operations
3. **Cache-Aligned** - Prevents false sharing between producer/consumer
4. **Zero-Copy** - `frontPtr()` allows direct memory access
5. **Single Producer/Single Consumer** - Optimal for pipeline architectures

### Performance Characteristics

#### Expected Performance (Based on Folly Benchmarks):
- **Single-threaded**: 10-20 ns per operation
- **Multi-threaded**: 30-100 ns per element (depending on contention)
- **Throughput**: 10-100M ops/sec per thread pair

#### Cache Behavior:
- Producer and consumer operate on separate cache lines
- Minimal cache coherency traffic
- False sharing eliminated by design

## Use Cases in MicroOptimus

### 1. Market Data Pipeline
```cpp
// Gateway → Recombinor communication
folly::ProducerConsumerQueue<MarketDataTick> mdQueue(4096);

// Producer thread (Gateway)
void onMarketData(const MarketDataTick& tick) {
    while (!mdQueue.write(tick)) {
        // Very rare with proper sizing
    }
}

// Consumer thread (Recombinor)
void processMarketData() {
    MarketDataTick tick;
    while (mdQueue.read(tick)) {
        reconstructBook(tick);
    }
}
```

### 2. Order Execution Pipeline
```cpp
// Signal → OSM communication
folly::ProducerConsumerQueue<OrderRequest> orderQueue(1024);

// Producer: Signal module
void generateOrder(const OrderRequest& order) {
    orderQueue.write(order);  // Non-blocking
}

// Consumer: OSM matching engine
void matchOrders() {
    OrderRequest order;
    if (orderQueue.read(order)) {
        processOrder(order);
    }
}
```

### 3. Execution Reports
```cpp
// OSM → Signal/Liquidator communication
folly::ProducerConsumerQueue<ExecutionReport> execQueue(2048);

// Producer: OSM
void onExecution(const ExecutionReport& exec) {
    execQueue.write(exec);
}

// Consumer: Risk/Position tracking
void updatePositions() {
    ExecutionReport exec;
    while (execQueue.read(exec)) {
        updatePosition(exec);
    }
}
```

### 4. Zero-Copy Pattern (Pointers)
```cpp
// For large messages, use pointer passing
struct LargeMessage {
    char data[1024];
};

folly::ProducerConsumerQueue<LargeMessage*> ptrQueue(512);

// Producer allocates from memory pool
LargeMessage* msg = pool.allocate();
fillMessage(msg);
ptrQueue.write(msg);

// Consumer uses and returns to pool
LargeMessage* msg;
if (ptrQueue.read(msg)) {
    process(msg);
    pool.free(msg);
}
```

## Building and Running

### Prerequisites
```bash
# macOS
brew install folly

# Linux (Ubuntu/Debian)
sudo apt-get install libfolly-dev

# Linux (from source)
git clone https://github.com/facebook/folly.git
cd folly
mkdir build && cd build
cmake ..
make -j$(nproc)
sudo make install
```

### Build with Folly Support
```bash
# From project root
./build_sor_with_folly.sh
```

### Run Tests Manually
```bash
cd build_with_folly/liquidator/src/main/cpp
./folly_spsc_queue_test
```

### Run Specific Tests
```bash
# Run only multi-threaded tests
./folly_spsc_queue_test --gtest_filter=*MultiThreaded*

# Run performance tests
./folly_spsc_queue_test --gtest_filter=*Performance*

# List all tests
./folly_spsc_queue_test --gtest_list_tests
```

## Example Usage Patterns

### Basic Producer-Consumer
```cpp
#include <folly/ProducerConsumerQueue.h>
#include <thread>

folly::ProducerConsumerQueue<int> queue(1024);

// Producer thread
std::thread producer([&]() {
    for (int i = 0; i < 1000000; ++i) {
        while (!queue.write(i)) {
            // Spin if queue full (rare with proper sizing)
        }
    }
});

// Consumer thread
std::thread consumer([&]() {
    int value;
    int count = 0;
    while (count < 1000000) {
        if (queue.read(value)) {
            process(value);
            count++;
        }
    }
});

producer.join();
consumer.join();
```

### Zero-Copy with frontPtr
```cpp
folly::ProducerConsumerQueue<Order> queue(512);

// Producer writes
Order order{1, 100.5, 1000};
queue.write(order);

// Consumer zero-copy read
const Order* orderPtr = queue.frontPtr();
if (orderPtr != nullptr) {
    processOrder(*orderPtr);  // No copy
    queue.popFront();         // Mark as consumed
}
```

### Batch Processing
```cpp
folly::ProducerConsumerQueue<Tick> queue(4096);

// Consumer batch reads
std::vector<Tick> batch;
batch.reserve(100);

Tick tick;
while (batch.size() < 100 && queue.read(tick)) {
    batch.push_back(tick);
}

if (!batch.empty()) {
    processBatch(batch);
}
```

## Integration with Smart Order Router

### Real-time Venue Updates
```cpp
class VenueScorer {
private:
    struct VenueUpdate {
        VenueId venue;
        double fillRate;
        uint64_t latency;
    };
    
    folly::ProducerConsumerQueue<VenueUpdate> updateQueue_{1024};
    std::thread updateThread_;
    
public:
    // Called from venue monitoring thread (producer)
    void reportVenueMetrics(VenueId venue, double fillRate, uint64_t latency) {
        updateQueue_.write(VenueUpdate{venue, fillRate, latency});
    }
    
    // Called from SOR thread (consumer)
    void processVenueUpdates() {
        VenueUpdate update;
        while (updateQueue_.read(update)) {
            updateVenueScore(update.venue, update.fillRate, update.latency);
        }
    }
};
```

### Order Flow Tracking
```cpp
class OrderFlowAnalyzer {
private:
    folly::ProducerConsumerQueue<OrderSnapshot> orderFlow_{2048};
    
public:
    // Producer: Order router
    void recordOrder(const OrderSnapshot& snapshot) {
        orderFlow_.write(snapshot);
    }
    
    // Consumer: Analytics thread
    void analyzeOrderFlow() {
        OrderSnapshot snapshot;
        std::vector<OrderSnapshot> window;
        window.reserve(100);
        
        while (orderFlow_.read(snapshot)) {
            window.push_back(snapshot);
            if (window.size() >= 100) {
                computeOrderFlowMetrics(window);
                window.clear();
            }
        }
    }
};
```

## Performance Tips

### 1. Queue Sizing
```cpp
// Size based on expected burst size, not average
// Rule of thumb: 2-4x max burst size
folly::ProducerConsumerQueue<T> queue(4096);  // Good for bursts up to 1000-2000
```

### 2. Avoid Spinning
```cpp
// Bad: Wastes CPU
while (!queue.write(data)) {
    // Busy spin
}

// Better: Yield to scheduler
while (!queue.write(data)) {
    std::this_thread::yield();
}

// Best: Size queue appropriately to avoid full condition
```

### 3. Use frontPtr for Zero-Copy
```cpp
// Slower: Copy on read
T value;
if (queue.read(value)) {
    process(value);
}

// Faster: Zero-copy
const T* ptr = queue.frontPtr();
if (ptr != nullptr) {
    process(*ptr);
    queue.popFront();
}
```

### 4. Batch Processing
```cpp
// Process multiple items per iteration
// Reduces overhead from checking isEmpty()
const int BATCH_SIZE = 32;
for (int i = 0; i < BATCH_SIZE; ++i) {
    T value;
    if (!queue.read(value)) break;
    process(value);
}
```

## Comparison with Other Queues

### SPSC Queue vs Alternatives

| Feature | Folly SPSC | boost::lockfree::spsc_queue | std::queue |
|---------|------------|------------------------------|------------|
| Lock-free | ✅ Yes | ✅ Yes | ❌ No (needs mutex) |
| Wait-free | ✅ Yes | ❌ No | ❌ No |
| Cache-aligned | ✅ Yes | ⚠️ Partial | ❌ No |
| Zero-copy read | ✅ frontPtr() | ❌ No | ❌ No |
| Fixed size | ✅ Yes | ✅ Yes | ❌ Dynamic |
| Performance | 🚀 10-20ns | ⚡ 20-40ns | 🐌 100-500ns |

**Winner: Folly SPSC** for ultra-low-latency single-producer/single-consumer scenarios.

## CMake Configuration

The SPSC test is **conditionally compiled** based on:

```cmake
option(USE_FOLLY "Use Folly libraries" ON)
```

When enabled:
- CMake searches for Folly using CONFIG mode
- Links against Folly::folly target
- Registers tests with CTest

## Common Issues

### Issue: Folly Not Found
```bash
# Check installation
brew list folly  # macOS
dpkg -l | grep folly  # Linux

# Set CMAKE_PREFIX_PATH
cmake -DCMAKE_PREFIX_PATH=/path/to/folly ...
```

### Issue: Linker Errors
Folly has many dependencies. Ensure all are installed:
```bash
# macOS
brew install folly  # Installs all dependencies

# Linux - install dependencies first
sudo apt-get install \
    libboost-all-dev \
    libevent-dev \
    libdouble-conversion-dev \
    libgoogle-glog-dev \
    libgflags-dev \
    libiberty-dev \
    liblz4-dev \
    liblzma-dev \
    libsnappy-dev \
    libzstd-dev
```

## Test File Location

```
liquidator/src/main/cpp/test/test_folly_spsc_queue.cpp
```

## Dependencies

- **Folly** 2020.01.01+ (ProducerConsumerQueue)
- **Google Test** 1.14.0 (auto-fetched)
- **C++17** standard library
- **pthread** (for multi-threaded tests)

## Future Enhancements

- [ ] Add tests with different message sizes
- [ ] Benchmark against boost::lockfree::spsc_queue
- [ ] Integration example with SBE messages
- [ ] Memory pool integration test
- [ ] Contention scenarios (oversubscribed cores)

## References

- [Folly ProducerConsumerQueue](https://github.com/facebook/folly/blob/main/folly/ProducerConsumerQueue.h)
- [Lock-Free Data Structures](https://www.1024cores.net/home/lock-free-algorithms)
- [Cache Line Alignment](https://mechanical-sympathy.blogspot.com/2011/07/false-sharing.html)

## License

See LICENSE file in project root.

