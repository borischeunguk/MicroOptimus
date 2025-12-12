# Smart Order Router (SOR) Implementation

## Overview

The Smart Order Router is a C++ ultra-low latency order routing system integrated into the MicroOptimus liquidator module. It provides intelligent order routing decisions with sub-microsecond latency while maintaining integration with the existing Aeron Cluster global sequencer architecture.

## Architecture

### Design Decision: SOR in LIQUIDATOR Module

**Clear Separation of Concerns:**

- **OSM (Order State Manager)**: Pure internal matching, orderbook management, CoralME design patterns
- **LIQUIDATOR (with SOR)**: Smart routing decisions, venue selection, external connectivity

### Message Flow

```
Signal/MM → OSM (try internal match) → LIQUIDATOR/SOR → External Venues
           ↑                              ↓
    Internal Execution              Smart Routing
    (~200ns)                        (~500ns)
```

## Features

### 1. Ultra-Low Latency C++ Core
- **Target Latency**: <500ns routing decisions
- **Boost Containers**: Flat maps, small vectors for cache efficiency
- **Folly Utilities**: Facebook's high-performance string and containers
- **Thread-Local Storage**: Minimize allocation overhead
- **SIMD Optimizations**: Modern CPU instruction usage

### 2. Smart Venue Selection
- **Multi-Factor Scoring**: Latency, fill rate, fees, order size
- **Venue Priorities**: Configurable venue preferences
- **Risk Checks**: Pre-trade validation and limits
- **Market Hours Awareness**: Time-based venue selection

### 3. Order Splitting
- **Large Order Handling**: Intelligent fragmentation across venues
- **Capacity Management**: Venue-specific order size limits
- **Priority-Based Allocation**: Optimal venue distribution

### 4. Supported Venues
- **INTERNAL**: Route back to OSM for passive liquidity
- **CME**: Futures/options via iLink3 protocol
- **NASDAQ**: Equities via OUCH protocol
- **NYSE**: Equities via FIX protocol
- **Extensible**: Easy addition of new venues

## Integration with Existing Architecture

### Maintains Global Sequencer + Shared Memory
```
MDR → Shared Memory → Cluster → Signal/MM → OSM → LIQUIDATOR/SOR
                                 ↑                      ↓
                          Internal Match           External Route
                             (200ns)                 (500ns)
```

### Stream Configuration
```java
// New stream IDs for SOR integration
OSM_TO_SOR_STREAM = 3001;     // OSM → SOR orders
SOR_TO_OSM_STREAM = 3002;     // SOR → OSM executions
SOR_CLUSTER_STREAM = 3003;    // SOR ↔ Cluster coordination
```

## Implementation Components

### Java Interface
```java
SmartOrderRouter sor = new SmartOrderRouter();
sor.initialize(configPath, sharedMemoryPath);

RoutingDecision decision = sor.routeOrder(orderRequest);
```

### C++ Core
```cpp
class SmartOrderRouter {
    VenueScorer venueScorer_;
    RiskManager riskManager_;
    OrderSplitter orderSplitter_;
    
public:
    RoutingDecision routeOrder(const OrderRequest& order);
};
```

### Integration Service
```java
SmartOrderRoutingService service = new SmartOrderRoutingService(
    configPath, sharedMemoryPath);
service.start();
```

## Building and Testing

### Prerequisites

#### macOS (Homebrew)
```bash
brew install boost folly cmake
```

#### Linux (Ubuntu/Debian)
```bash
sudo apt-get install libboost-all-dev libfolly-dev cmake build-essential
```

### Build Commands

```bash
# Install C++ dependencies (one-time setup)
./gradlew :liquidator:installCppDependencies

# Compile C++ SOR and Java interface
./gradlew :liquidator:build

# Run performance tests
./gradlew :liquidator:runSORTest

# C++ standalone performance test
cd liquidator/src/main/cpp
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
make
./sor_perf_test
```

## Performance Characteristics

### Target Performance
```
Routing Decision Latency: <500ns average
Throughput: >1M orders/sec
P99 Latency: <2μs
Memory Usage: <100MB
```

### Benchmark Results (Expected)
```
Average Latency: ~300ns
P99 Latency: ~1.5μs
Throughput: ~2M orders/sec
Internal Route Rate: ~20%
```

## Configuration

### Venue Configuration
```java
// Configure CME venue
VenueConfig cmeConfig = new VenueConfig(
    90,         // priority
    true,       // enabled
    1_000_000,  // maxOrderSize
    0.15,       // avgLatencyMicros
    0.95,       // fillRate
    0.0001      // feesPerShare
);
sor.configureVenue(VenueType.CME, cmeConfig);
```

### Risk Limits
```cpp
// C++ risk manager configuration
riskManager.setMaxOrderSize(1000000);
riskManager.setMaxOrderValue(100000000);
riskManager.setMaxPriceDeviation(0.1); // 10%
```

## Integration Examples

### OSM Integration
```java
// In OSM - when internal matching fails
if (!internalMatch) {
    // Send order to SOR via Aeron IPC
    sendOrderToSOR(order, OSM_TO_SOR_STREAM);
}
```

### Venue Gateway Integration
```java
// In CMEOrderGateway
switch (routingDecision.primaryVenue) {
    case CME:
        sendToCME(order);
        break;
    case NASDAQ:
        sendToNasdaq(order);
        break;
}
```

## Monitoring and Statistics

### Runtime Statistics
```java
RoutingStats stats = sor.getStatistics();
System.out.println("Internal route rate: " + stats.getInternalRoutingRate() + "%");
System.out.println("Average latency: " + stats.avgLatencyNanos + "ns");
```

### JVM Arguments
```
-Djava.library.path=liquidator/src/main/cpp/build
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
-XX:+UseZGC  # Ultra-low pause GC
```

## Development Guidelines

### Adding New Venues
1. Add venue enum to `VenueType`
2. Implement venue-specific routing in `routeToVenue()`
3. Add configuration parameters
4. Update venue scorer algorithm
5. Add protocol-specific gateway

### Performance Optimization
- Use thread-local storage for frequent allocations
- Minimize dynamic memory allocation in hot paths
- Leverage SIMD instructions where possible
- Profile with `perf` on Linux or `Instruments` on macOS

### Testing
- Unit tests for routing algorithms
- Performance benchmarks for latency validation
- Integration tests with Aeron cluster
- Load testing with realistic order patterns

## Troubleshooting

### Common Issues

#### C++ Library Not Found
```bash
export LD_LIBRARY_PATH=liquidator/src/main/cpp/build:$LD_LIBRARY_PATH
```

#### JNI Compilation Errors
```bash
# Regenerate JNI headers
./gradlew :liquidator:generateJNIHeaders
```

#### Performance Issues
- Check CPU affinity and NUMA topology
- Verify GC settings (use ZGC or G1)
- Monitor memory allocation patterns
- Use CPU profiler to identify hotspots

### Debug Mode
```bash
# Compile with debug symbols
cmake .. -DCMAKE_BUILD_TYPE=Debug
# Run with GDB
gdb ./sor_perf_test
```

## Future Enhancements

### Planned Features
- Machine learning venue selection
- Real-time market impact modeling
- Cross-venue arbitrage detection
- Advanced order types (Iceberg, TWAP, VWAP)
- WebSocket market data integration

### Scalability Improvements
- Lock-free data structures
- DPDK network acceleration
- GPU-accelerated venue scoring
- Distributed SOR across multiple processes

## Contributing

1. Follow C++17 standards
2. Maintain sub-microsecond latency targets
3. Add comprehensive unit tests
4. Document performance implications
5. Validate integration with existing Aeron cluster

## License

Same as MicroOptimus project license.
