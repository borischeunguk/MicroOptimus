# MicroOptimus

Objective:

Design and implement Sequencer ( Coral C++, Lmax Disruptor or Aeron Java ) based orderbook and matching engine ( java ), market data processer (java),
order gateway ( c++ ), and market data gateway ( c++ ), market making ( java ) 

lock free, gc free, zero copy, shared memory based high performance system for trading.

Sequencer can start from lmax disruptor, though it should be interfaced to allow swapping to Coral Ring or Aeron Cluster later.

Use CME Exchange as example for implementation.

Both FIX and SBE protocols should be supported at order gateway and market data gateway, with SBE preferred for low latency.

Versions:

MVP using lmax disruptor: components all communicate via shared memory, single process for all components, single threaded orderbook and matching engine, single threaded market data processor, single threaded market data gateway, single threaded order gateway.

Standard Java Version using Aeron Cluster: support multi processes, multi nodes deployment

Advanced Version using Coral Blocks C++ : lock free, gc free, zero copy, shared memory based high performance system for trading.

Benchmark: 

Ideally:
latency < 500 nano seconds end to end from order entry to order ack, 
throughput > 30 million orders per second

Acceptable:
latency < 2 micro seconds end to end from order entry to order ack, 
throughput > 10 million orders per second

Reference:

lmax disruptor: https://github.com/LMAX-Exchange/disruptor

Aeron: https://github.com/aeron-io
# Aeron cookbook: https://github.com/aeron-io/aeron-cookbook-code
# Aeron : https://github.com/aeron-io/aeron

Coral Blocks: https://github.com/coralblocks
# Coral Me:https://github.com/coralblocks/CoralME
# Coral Ring: https://github.com/coralblocks/CoralRing
# Coral Queue: https://github.com/coralblocks/CoralQueue
# Coral Pool: https://github.com/coralblocks/CoralPool


## MVP Architecture Sketch

**Module Names:**
- **OSM** (Order State Manager) - Orderbook + Matching Engine
- **OSM-Signal** - Market Making strategy
- **Recombinor** - Market Data Processor
- **Liquidator** - Order Exchange Connectivity (connects OSM to CME)
- **Gateway** - Market Data Connectivity (receives from CME)

---

## Available Sequencers

| **Sequencer** | **License** | **Use Case** | **Status** |
|---------------|-------------|--------------|------------|
| **LMAX Disruptor** | ✅ Apache 2.0 (Free) | Single-process, inter-thread | **Use for MVP** |
| **Aeron Cluster** | ✅ Apache 2.0 (Free) | Multi-process, consensus | **Use for Phase 2** |
| **Coral Sequencer** | ❌ Proprietary | Commercial product | Not available |

**Decision:** Use LMAX Disruptor for MVP, migrate to Aeron Cluster for Phase 2

---

## MVP Architecture Design

### Module Responsibilities

1. **OSM-Signal (Market Making)** - Thread 1
    - Consumes market data from Recombinor
    - Runs market making strategy
    - Generates orders/quotes
    - Publishes to OSM

2. **OSM (Order State Manager)** - Thread 2
    - Consumes orders from OSM-Signal
    - Maintains internal orderbook
    - Decides: match internally OR route to CME
    - Publishes internal executions to Recombinor
    - Publishes external orders to Liquidator
    - Consumes CME fills from Liquidator

3. **Liquidator (CME Order Gateway)** - Thread 3
    - Consumes orders needing CME execution from OSM
    - Encodes to FIX/SBE
    - Sends to CME exchange
    - Receives execution reports from CME
    - Publishes CME fills back to OSM

4. **Recombinor (Market Data Processor)** - Thread 4
    - Consumes internal executions from OSM
    - Consumes external CME market data from Gateway
    - Combines and processes data
    - Maintains consolidated market view
    - Publishes to OSM-Signal

5. **Gateway (CME Market Data)** - Thread 5
    - Receives CME market data feed (UDP multicast)
    - Decodes SBE/FIX messages
    - Publishes to Recombinor

### RingBuffer Coordination
### RingBuffer Table

| **RingBuffer** | **Event Type** | **Producer** | **Consumer** | **Size** | **WaitStrategy** | **Purpose** |
|----------------|----------------|--------------|--------------|----------|------------------|-------------|
| **RB-1** | `OrderRequest` | OSM-Signal | OSM | 8192 | BusySpin | Strategy orders/quotes |
| **RB-2** | `ExternalOrderEvent` | OSM | Liquidator | 8192 | BusySpin | Orders to CME |
| **RB-3** | `ExternalExecutionEvent` | Liquidator | OSM | 4096 | BusySpin | CME fills |
| **RB-4** | `InternalExecutionEvent` | OSM | Recombinor | 4096 | BusySpin | Internal matches |
| **RB-5** | `MarketDataEvent` | Recombinor | OSM-Signal | 4096 | BusySpin | Consolidated MD |
| **RB-6** | `ExternalMarketDataEvent` | Gateway | Recombinor | 4096 | BusySpin | CME MD feed |

### Thread Model

| **Thread** | **Module** | **CPU Core** | **Role** |
|------------|------------|--------------|----------|
| T1 | OSM-Signal (Market Making) | Core 0 | Generate quotes from market data |
| T2 | OSM (Matching Engine) | Core 1 | Match internally or route to CME |
| T3 | Liquidator (CME Order Gateway) | Core 2 | Send/receive orders to/from CME |
| T4 | Recombinor (MD Processor) | Core 3 | Consolidate internal + external MD |
| T5 | Gateway (CME MD Inbound) | Core 4 | Receive CME market data feed |

### Order Flow Scenarios

**Scenario 1: Internal Match**
**Scenario 2: CME Execution**
**Scenario 3: Market Data Flow**

### Project Structure

MicroOptimus/
├── build.gradle
├── settings.gradle
├── gradle.properties
│
├── common/          # Shared events, utils
│   └── src/main/java/com/microoptimus/common/
│       ├── events/
│       │   ├── OrderRequest.java
│       │   ├── ExternalOrderEvent.java
│       │   ├── ExternalExecutionEvent.java
│       │   ├── InternalExecutionEvent.java
│       │   ├── MarketDataEvent.java
│       │   └── ExternalMarketDataEvent.java
│       ├── types/
│       │   ├── Side.java
│       │   ├── OrderType.java
│       │   └── TimeInForce.java
│       └── util/
│           └── PriceUtils.java
│
├── osm/              # Order State Manager
│   └── src/
│       ├── main/java/com/microoptimus/osm/
│       │   ├── OrderStateManager.java
│       │   ├── OrderBook.java
│       │   ├── MatchingEngine.java
│       │   ├── PriceLevel.java
│       │   ├── Order.java
│       │   ├── RoutingDecision.java
│       │   └── PositionTracker.java
│       ├── test/java/             # Unit tests
│       └── jmh/java/              # JMH benchmarks
│
├── liquidator/       # CME Order Gateway
│   └── src/main/java/com/microoptimus/liquidator/
│       ├── CMEOrderGateway.java
│       ├── CMESession.java
│       ├── OrderSender.java
│       ├── ExecutionReceiver.java
│       └── OrderIDMapper.java
│
├── marketdata-recombinor/       # Market Data Processor
│   └── src/main/java/com/microoptimus/marketdata-recombinor/
│       ├── MarketDataProcessor.java
│       ├── BookCombiner.java
│       └── MarketDataPublisher.java
│
├── marketdata-gateway/          # CME Market Data
│   └── src/main/java/com/microoptimus/marketdata-gateway/
│       ├── CMEMarketDataGateway.java
│       ├── UDPReceiver.java
│       └── GapHandler.java
│
├── osm-signal/           # Market Making
│   └── src/main/java/com/microoptimus/osm-signal/
│       ├── MarketMakingStrategy.java
│       ├── QuoteCalculator.java
│       └── InventoryManager.java
│
└── microoptimus-app/              # Main application
└── src/
├── main/java/com/microoptimus/app/
│   ├── MicroOptimusApp.java
│   └── DisruptorWiring.java
└── test/java/
└── integration/        # Integration tests






