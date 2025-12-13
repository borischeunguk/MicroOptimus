package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark for UnifiedOrderBookWithPriority
 *
 * Tests performance of internalization priority matching against your exact scenario:
 * - Multiple liquidity sources in one book
 * - Priority matching: INTERNAL > SIGNAL > EXTERNAL
 * - Normal CoralME-based performance characteristics
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class UnifiedOrderBookBenchmark {

    private UnifiedOrderBookWithPriority book;
    private long orderIdCounter = 1000;

    @Setup(Level.Trial)
    public void setup() {
        book = new UnifiedOrderBookWithPriority("AAPL");

        // Pre-populate with liquidity from different sources (your scenario)
        // This simulates a realistic book state before benchmarking

        // Signal/MM orders
        book.addSignalMMOrder(nextOrderId(), 100, Side.BUY, 95, 100, TimeInForce.GTC);
        book.addSignalMMOrder(nextOrderId(), 100, Side.BUY, 94, 200, TimeInForce.GTC);
        book.addSignalMMOrder(nextOrderId(), 100, Side.SELL, 105, 100, TimeInForce.GTC);
        book.addSignalMMOrder(nextOrderId(), 100, Side.SELL, 106, 200, TimeInForce.GTC);

        // External exchange orders
        book.addExternalExchangeOrder("CME", Side.BUY, 95, 50);
        book.addExternalExchangeOrder("CME", Side.BUY, 94, 100);
        book.addExternalExchangeOrder("CME", Side.SELL, 105, 50);
        book.addExternalExchangeOrder("CME", Side.SELL, 106, 100);

        book.addExternalExchangeOrder("NASDAQ", Side.BUY, 93, 300);
        book.addExternalExchangeOrder("NASDAQ", Side.SELL, 107, 300);

        // Internal trader orders
        book.addInternalTraderOrder(nextOrderId(), 400, Side.BUY, 95, 75, TimeInForce.GTC);
        book.addInternalTraderOrder(nextOrderId(), 500, Side.BUY, 94, 150, TimeInForce.GTC);
        book.addInternalTraderOrder(nextOrderId(), 600, Side.SELL, 105, 75, TimeInForce.GTC);
        book.addInternalTraderOrder(nextOrderId(), 700, Side.SELL, 106, 150, TimeInForce.GTC);
    }

    /**
     * Benchmark adding internal trader order (highest priority)
     */
    @Benchmark
    public Order benchmarkAddInternalOrder(Blackhole bh) {
        Order order = book.addInternalTraderOrder(nextOrderId(), 400, Side.BUY, 95, 10, TimeInForce.GTC);
        bh.consume(order);
        return order;
    }

    /**
     * Benchmark adding signal/MM order (medium priority)
     */
    @Benchmark
    public Order benchmarkAddSignalOrder(Blackhole bh) {
        Order order = book.addSignalMMOrder(nextOrderId(), 100, Side.BUY, 95, 10, TimeInForce.GTC);
        bh.consume(order);
        return order;
    }

    /**
     * Benchmark adding external exchange order (lowest priority)
     */
    @Benchmark
    public Order benchmarkAddExternalOrder(Blackhole bh) {
        Order order = book.addExternalExchangeOrder("CME", Side.BUY, 95, 10);
        bh.consume(order);
        return order;
    }

    /**
     * Benchmark aggressive internal order (tests priority matching)
     * This simulates your exact scenario: internal order matching with priority
     */
    @Benchmark
    public Order benchmarkInternalAggressiveOrder(Blackhole bh) {
        // Aggressive sell that will match with multiple liquidity sources
        Order order = book.addInternalTraderOrder(nextOrderId(), 800, Side.SELL, 94, 50, TimeInForce.IOC);
        bh.consume(order);
        return order;
    }

    /**
     * Benchmark aggressive external order (tests how external orders interact)
     */
    @Benchmark
    public Order benchmarkExternalAggressiveOrder(Blackhole bh) {
        // Aggressive buy from external venue
        Order order = book.addExternalExchangeOrder("NYSE", Side.BUY, 106, 25);
        bh.consume(order);
        return order;
    }

    /**
     * Benchmark mixed scenario (realistic trading pattern)
     */
    @Benchmark
    public void benchmarkMixedScenario(Blackhole bh) {
        // Add orders from different sources (realistic mix)
        Order internal = book.addInternalTraderOrder(nextOrderId(), 400, Side.BUY, 94, 25, TimeInForce.GTC);
        Order signal = book.addSignalMMOrder(nextOrderId(), 100, Side.SELL, 106, 25, TimeInForce.GTC);
        Order external = book.addExternalExchangeOrder("CME", Side.BUY, 95, 15);

        // Aggressive order that triggers matching
        Order aggressive = book.addInternalTraderOrder(nextOrderId(), 900, Side.SELL, 93, 100, TimeInForce.IOC);

        bh.consume(internal);
        bh.consume(signal);
        bh.consume(external);
        bh.consume(aggressive);
    }

    /**
     * Benchmark your EXACT scenario from the demo
     */
    @Benchmark
    public void benchmarkExactScenario(Blackhole bh) {
        // Setup your exact scenario
        Order bid1 = book.addSignalMMOrder(1001, 100, Side.BUY, 9, 5, TimeInForce.GTC);   // bid1
        Order bid2 = book.addExternalExchangeOrder("Exchange1", Side.BUY, 9, 5);          // bid2
        Order bid5 = book.addExternalExchangeOrder("Exchange2", Side.BUY, 8, 5);          // bid5
        Order bid3 = book.addInternalTraderOrder(3001, 400, Side.BUY, 9, 5, TimeInForce.GTC); // bid3

        // The aggressive order that triggers priority matching
        Order ask4 = book.addInternalTraderOrder(3002, 500, Side.SELL, 9, 12, TimeInForce.IOC); // ask4

        bh.consume(bid1);
        bh.consume(bid2);
        bh.consume(bid5);
        bh.consume(bid3);
        bh.consume(ask4);
    }

    /**
     * Benchmark getting book statistics
     */
    @Benchmark
    public UnifiedOrderBookWithPriority.UnifiedOrderBookStats benchmarkGetStats(Blackhole bh) {
        UnifiedOrderBookWithPriority.UnifiedOrderBookStats stats = book.getUnifiedStats();
        bh.consume(stats);
        return stats;
    }

    private long nextOrderId() {
        return orderIdCounter++;
    }
}
