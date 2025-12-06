# MicroOptimus Architecture

## Overview

MicroOptimus is a high-performance trading system that implements a Sequencer-Based Orderbook, MatchEngine, and Market Making capabilities. The system is designed as a hybrid architecture combining the strengths of C++, Java, and Python.

## System Components

### 1. C++ Core Components (High Performance)

#### Orderbook (Sequencer-Based)
- **Location**: `cpp/orderbook/`
- **Purpose**: Maintains orders with price-time priority
- **Key Features**:
  - Deterministic order sequencing
  - Price-level aggregation
  - Thread-safe operations with mutex protection
  - O(log n) order insertion and deletion
  - Market depth queries

**Data Structures**:
- `std::map` with custom comparators for bid/ask levels
- Separate buy/sell order maps for efficient matching
- Price levels contain lists of orders at same price

#### MatchEngine
- **Location**: `cpp/matchengine/`
- **Purpose**: Matches orders and executes trades
- **Key Features**:
  - Price-time priority matching algorithm
  - Support for multiple order types (LIMIT, MARKET, IOC, FOK)
  - Trade generation and notification
  - Matching statistics tracking

**Matching Logic**:
1. Aggressive orders match against passive orders in the book
2. Trades execute at the passive order's price
3. Orders updated after each fill
4. Unfilled quantities handled per order type

### 2. Java Components (Connectivity & Gateway)

#### Market Data Feed
- **Location**: `java/src/main/java/com/microoptimus/marketdata/`
- **Purpose**: Receives and distributes market data
- **Key Features**:
  - Real-time market data subscription
  - Event-driven architecture with listeners
  - Thread-safe market data distribution
  - Simulation mode for testing

#### Exchange Session
- **Location**: `java/src/main/java/com/microoptimus/exchange/`
- **Purpose**: Manages exchange connectivity and order lifecycle
- **Key Features**:
  - Order submission and cancellation
  - Order state tracking
  - Exchange connectivity management
  - FIX protocol support (ready for integration)

#### Order Gateway
- **Location**: `java/src/main/java/com/microoptimus/gateway/`
- **Purpose**: Bridges trading strategies with exchanges
- **Key Features**:
  - Unified interface for order submission
  - Market data and order status aggregation
  - Event routing between components

### 3. Python Components (Strategy)

#### Market Maker
- **Location**: `python/marketmaker/`
- **Purpose**: Provides liquidity through continuous quoting
- **Key Features**:
  - Spread-based quoting strategy
  - Inventory management with position tracking
  - Risk management (position limits, loss limits)
  - Inventory skewing to manage risk
  - P&L calculation (realized and unrealized)

**Strategy Logic**:
1. Calculate quotes based on mid price and spread
2. Apply inventory skewing based on current position
3. Check risk limits before quoting
4. Update positions on fills
5. Track P&L continuously

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Python Layer (Strategy)                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Market Maker Strategy                    │   │
│  │  - Quoting Logic                                      │   │
│  │  - Inventory Management                               │   │
│  │  - Risk Management                                    │   │
│  └──────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Java Layer (Connectivity & Gateway)             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────┐ │
│  │  Market Data     │  │  Exchange        │  │   Order   │ │
│  │  Feed            │  │  Session         │  │  Gateway  │ │
│  │                  │  │                  │  │           │ │
│  │  - Subscriptions │  │  - Order Routing │  │  - Bridge │ │
│  │  - Distribution  │  │  - State Mgmt    │  │  - Events │ │
│  └──────────────────┘  └──────────────────┘  └───────────┘ │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│            C++ Layer (High-Performance Matching)             │
│  ┌──────────────────┐           ┌──────────────────┐        │
│  │   Sequencer      │           │   MatchEngine    │        │
│  │   OrderBook      │◄──────────┤                  │        │
│  │                  │           │  - Matching      │        │
│  │  - Price Levels  │           │  - Trade Gen     │        │
│  │  - Sequencing    │           │  - Statistics    │        │
│  │  - Thread Safe   │           │                  │        │
│  └──────────────────┘           └──────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

## Data Flow

### Order Flow
1. Strategy (Python) generates order based on market data
2. Order routed through Gateway (Java)
3. Exchange Session submits to exchange or internal matching
4. For internal matching: C++ MatchEngine processes against OrderBook
5. Trade confirmations flow back through Gateway to Strategy

### Market Data Flow
1. Market Data Feed (Java) receives updates from exchange/simulation
2. Updates distributed to registered listeners
3. Strategy (Python) receives data through bridge
4. Strategy updates quotes and positions

## Performance Characteristics

### C++ Components
- **Latency**: Sub-microsecond order processing
- **Throughput**: 1M+ orders per second
- **Memory**: O(n) where n = active orders

### Java Components
- **Latency**: Single-digit milliseconds
- **Throughput**: 100K+ messages per second
- **Memory**: Efficient with connection pooling

### Python Components
- **Latency**: 10-100 milliseconds
- **Suitable For**: Strategy logic, not ultra-low latency execution

## Threading Model

### C++ (Thread-Safe)
- Mutex-protected orderbook operations
- Lock-free reads where possible
- Single writer, multiple readers pattern

### Java (Event-Driven)
- Executor services for async operations
- Copy-on-write for listener lists
- Thread-safe collections

### Python (GIL-Limited)
- Suitable for strategy computation
- Can use multiprocessing for parallelism

## Extension Points

1. **New Order Types**: Extend Order.hpp and MatchEngine
2. **Custom Strategies**: Inherit from base strategy classes
3. **Exchange Adapters**: Implement ExchangeListener interface
4. **Market Data Sources**: Implement MarketDataListener interface

## Deployment

The system can be deployed in several configurations:

1. **Standalone**: All components in single process
2. **Distributed**: Components on separate machines
3. **Hybrid**: C++ matching engine on FPGA/dedicated hardware
4. **Cloud**: Containerized with Kubernetes orchestration
