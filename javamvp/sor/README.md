# javamvp-sor

This module now contains two routers:

- `SorMvpRouter`: existing baseline router (unchanged)
- `SorRouter`: new NAM router for `CBOE`, `NASQ`, `NYSE`

## SorRouter model

`SorRouter` selects a single best venue using:

- top-of-book executable price (`bestAsk` for BUY, `bestBid` for SELL)
- venue taker fee (bps)
- venue latency telemetry (p50 micros)
- available top-of-book size
- stale quote filter

The score is:

`score = wPrice*priceTerm - wFee*feeTerm - wLatency*latencyTerm + wSize*sizeTerm`

## Market data support

The router uses a market data abstraction:

- `MarketDataService`
- `SimulatedMarketDataService` (for deterministic tests and performance work)

This lets you keep benchmark velocity now and swap in a live service later.

## Quick run

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus/javamvp
./gradlew :sor:test
./gradlew :sor:runSorRouterDemo
```

## E2E JMH with SorRouter

`SorE2eServiceMain` supports `-Djavamvp.sor.router.impl=mvp|new` (`mvp` default).

Run E2E JMH with the new router:

```bash
cd /Users/xinyue/IdeaProjects/MicroOptimus
./gradlew -p javamvp :sor:runE2ELatency --no-daemon -PjavamvpE2eSamples=100000 -Djavamvp.sor.router.impl=new
```


