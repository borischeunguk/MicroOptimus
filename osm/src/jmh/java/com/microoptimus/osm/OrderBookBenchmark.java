package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for OrderBook performance
 * Tests GC-free operation and throughput
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OrderBookBenchmark {

    private OrderBook orderBook;
    private long orderIdCounter = 0;

    @Setup(Level.Iteration)
    public void setup() {
        orderBook = new OrderBook("AAPL");
        orderIdCounter = 0;
    }

    @Benchmark
    public Order benchmarkAddLimitOrder() {
        return orderBook.addLimitOrder(
            ++orderIdCounter,
            100L,
            Side.BUY,
            15000L,
            100L,
            TimeInForce.DAY
        );
    }

    @Benchmark
    public Order benchmarkMatchAtTopOfBook() {
        // Add resting order
        if (orderIdCounter % 2 == 0) {
            return orderBook.addLimitOrder(
                ++orderIdCounter,
                100L,
                Side.SELL,
                15000L,
                100L,
                TimeInForce.DAY
            );
        } else {
            // Match with aggressive order
            return orderBook.addLimitOrder(
                ++orderIdCounter,
                101L,
                Side.BUY,
                15000L,
                100L,
                TimeInForce.IOC
            );
        }
    }

    @Benchmark
    public Object benchmarkFullOrderLifecycle() {
        // Add order
        Order order = orderBook.addLimitOrder(
            ++orderIdCounter,
            100L,
            Side.BUY,
            15000L,
            100L,
            TimeInForce.DAY
        );

        // Cancel order
        orderBook.cancelOrder(order.getOrderId());

        return order;
    }

    /**
     * Test no GC scenario
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void benchmarkNoGC() {
        // Build up book
        for (int i = 0; i < 10; i++) {
            orderBook.addLimitOrder(
                ++orderIdCounter,
                100L,
                Side.BUY,
                15000L - i,
                100L,
                TimeInForce.DAY
            );

            orderBook.addLimitOrder(
                ++orderIdCounter,
                100L,
                Side.SELL,
                15100L + i,
                100L,
                TimeInForce.DAY
            );
        }

        // Match with IOC orders
        orderBook.addLimitOrder(
            ++orderIdCounter,
            101L,
            Side.BUY,
            15110L,
            100L,
            TimeInForce.IOC
        );

        orderBook.addLimitOrder(
            ++orderIdCounter,
            101L,
            Side.SELL,
            14990L,
            100L,
            TimeInForce.IOC
        );

        // Market orders to clear book
        orderBook.addMarketOrder(
            ++orderIdCounter,
            102L,
            Side.BUY,
            5000L
        );

        orderBook.addMarketOrder(
            ++orderIdCounter,
            102L,
            Side.SELL,
            5000L
        );
    }
}

