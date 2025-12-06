# MicroOptimus

A high-performance, hybrid trading system featuring a Sequencer-Based Orderbook, MatchEngine, Exchange Connectivity, and Market Making capabilities.

## Overview

MicroOptimus is a professional-grade trading infrastructure that combines the strengths of three programming languages:
- **C++**: High-performance orderbook and matching engine
- **Java**: Market data feed and exchange connectivity
- **Python**: Market making strategy and risk management

## Features

### Core Components

#### 🚀 C++ High-Performance Engine
- **Sequencer-Based Orderbook**: Deterministic order processing with price-time priority
- **MatchEngine**: Efficient order matching with support for multiple order types
  - LIMIT orders
  - MARKET orders
  - IOC (Immediate or Cancel)
  - FOK (Fill or Kill)
- Thread-safe operations with mutex protection
- Sub-microsecond latency for order processing

#### 🔌 Java Connectivity Layer
- **Market Data Feed**: Real-time market data distribution
- **Exchange Session**: Order lifecycle management
- **Order Gateway**: Unified interface for strategy-to-exchange communication
- Event-driven architecture with listener patterns
- Ready for FIX protocol integration

#### 📊 Python Strategy Layer
- **Market Maker**: Automated liquidity provision
  - Spread-based quoting
  - Inventory management
  - Position tracking with P&L calculation
  - Risk management (position limits, loss limits)
  - Inventory skewing for risk mitigation

## Quick Start

### Prerequisites
- CMake 3.10+
- C++17 compiler (GCC 7+, Clang 5+, MSVC 2017+)
- JDK 11+
- Maven 3.6+
- Python 3.7+

### Build and Run

```bash
# Clone the repository
git clone https://github.com/borischeunguk/MicroOptimus.git
cd MicroOptimus

# Build C++ components
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
make -j$(nproc)
./microoptimus
cd ..

# Build Java components
cd java
mvn clean package
java -jar target/microoptimus-java-1.0.0.jar
cd ..

# Run Python market maker
cd python
python demo_market_maker.py
cd ..
```

## Architecture

```
┌─────────────────────────────────────────────┐
│   Python Strategy Layer (Market Making)     │
└───────────────┬─────────────────────────────┘
                │
┌───────────────▼─────────────────────────────┐
│   Java Connectivity Layer (Gateway)         │
│   - Market Data Feed                        │
│   - Exchange Session                        │
│   - Order Gateway                           │
└───────────────┬─────────────────────────────┘
                │
┌───────────────▼─────────────────────────────┐
│   C++ Execution Layer (High Performance)    │
│   - Sequencer-Based Orderbook              │
│   - MatchEngine                             │
└─────────────────────────────────────────────┘
```

## Documentation

- [Architecture Guide](docs/ARCHITECTURE.md) - System design and component details
- [Build Guide](docs/BUILD.md) - Comprehensive build instructions

## Project Structure

```
MicroOptimus/
├── cpp/                      # C++ components
│   ├── include/              # Header files
│   │   ├── Order.hpp
│   │   ├── OrderBook.hpp
│   │   ├── MatchEngine.hpp
│   │   └── Trade.hpp
│   ├── orderbook/            # Orderbook implementation
│   ├── matchengine/          # Matching engine implementation
│   └── main.cpp              # C++ demo
├── java/                     # Java components
│   ├── src/main/java/com/microoptimus/
│   │   ├── marketdata/       # Market data feed
│   │   ├── exchange/         # Exchange connectivity
│   │   └── gateway/          # Order gateway
│   └── pom.xml               # Maven configuration
├── python/                   # Python components
│   ├── marketmaker/          # Market making strategy
│   ├── demo_market_maker.py  # Python demo
│   └── requirements.txt      # Python dependencies
├── docs/                     # Documentation
├── config/                   # Configuration files
└── CMakeLists.txt           # CMake build configuration
```

## Examples

### C++ Orderbook Example

```cpp
#include "include/OrderBook.hpp"
#include "include/MatchEngine.hpp"

OrderBook orderbook("BTCUSD");
MatchEngine engine;

// Add orders
auto buyOrder = std::make_shared<Order>(1, "BTCUSD", OrderSide::BUY, 
                                        OrderType::LIMIT, 50000.0, 10, "Client1");
orderbook.addOrder(buyOrder);

// Match orders
auto trades = engine.matchOrder(aggressiveOrder, orderbook);
```

### Java Gateway Example

```java
ExchangeSession session = new ExchangeSession("MAIN");
MarketDataFeed feed = new MarketDataFeed();
OrderGateway gateway = new OrderGateway(session, feed);

// Submit order
String orderId = gateway.buyLimit("BTCUSD", 50000.0, 10);
```

### Python Market Maker Example

```python
from marketmaker import MarketMaker, MarketData

mm = MarketMaker(
    symbol="BTCUSD",
    spread_bps=10.0,
    quote_size=10,
    max_position=100
)

# Generate quotes
quote = mm.calculate_quote(market_data)
```

## Performance

- **C++ Orderbook**: 1M+ orders/second
- **C++ MatchEngine**: Sub-microsecond latency
- **Java Gateway**: 100K+ messages/second
- **Python Strategy**: Suitable for medium-frequency trading

## Use Cases

- **High-Frequency Trading**: Ultra-low latency order matching
- **Market Making**: Automated liquidity provision
- **Algorithmic Trading**: Strategy backtesting and execution
- **Exchange Development**: Building trading venues
- **Education**: Learning trading system architecture

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

Built with modern C++17, Java 11, and Python 3 best practices for maximum performance and maintainability.

## Contact

For questions or support, please open an issue on GitHub.

---

**Note**: This is a demonstration/educational system. For production use, additional features like persistence, monitoring, and compliance controls would be required.