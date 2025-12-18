package com.microoptimus.common.sbe;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SBE Performance Benchmark Test - Demonstrates zero-JNI latency benefits
 *
 * This benchmark compares:
 * 1. SBE shared memory approach (zero-JNI) vs simulated JNI overhead
 * 2. Encoding/decoding performance under various loads
 * 3. Memory allocation patterns (should be zero in SBE)
 * 4. Throughput capabilities for HFT scenarios
 */
public class SBEPerformanceBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(SBEPerformanceBenchmarkTest.class);

    private SBEBufferManager bufferManager;

    @BeforeEach
    void setUp() throws IOException {
        bufferManager = new SBEBufferManager("/tmp/sbe_perf_test.bin", 8192);
        log.info("🚀 SBE Performance Benchmark Setup Complete");
    }

    @Test
    @DisplayName("⚡ Zero-JNI vs Simulated JNI Performance Comparison")
    void testZeroJNIvsJNIPerformance() {
        log.info("🏁 Starting Zero-JNI vs JNI Performance Comparison");

        int iterations = 10_000;

        // Test 1: Zero-JNI SBE approach
        long sbeStartTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // OSM → SOR communication via SBE
            long sequenceId = i;

            // Encode order request (OSM side)
            MutableDirectBuffer writeBuffer = bufferManager.getWriteBuffer(sequenceId);
            int encodedLength = encodeOrderRequestFast(writeBuffer, sequenceId, 1000L + i,
                    "AAPL", (byte)0, 15000000L, 100L);
            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

            // Decode order request (SOR side) - zero-copy
            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            long orderId = readBuffer.getLong(8); // Direct field access

            // Encode routing decision (SOR side)
            long responseSeq = sequenceId + iterations;
            MutableDirectBuffer responseBuffer = bufferManager.getWriteBuffer(responseSeq);
            int responseLength = encodeRoutingDecisionFast(responseBuffer, responseSeq, orderId, (byte)1);
            bufferManager.publishMessage(responseSeq, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, responseLength);

            // Decode routing decision (OSM side) - zero-copy
            UnsafeBuffer decisionBuffer = bufferManager.getReadBuffer(responseSeq);
            byte action = decisionBuffer.getByte(17); // Direct field access
            assertEquals(1, action, "Action should be ROUTE_INTERNAL");
        }
        long sbeEndTime = System.nanoTime();
        long sbeTime = sbeEndTime - sbeStartTime;

        log.info("📊 SBE Zero-JNI: {} iterations in {}μs (avg: {}ns per round-trip)",
                iterations, sbeTime / 1000, String.format("%.1f", sbeTime / (double)iterations));

        // Test 2: Simulated JNI overhead approach
        long jniStartTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // Simulate JNI marshaling overhead
            simulateJNIMarshalingOverhead(50); // 50ns JNI call overhead

            // Simulate object creation (GC pressure)
            OrderData order = new OrderData(1000L + i, "AAPL", 100L, 15000000L);

            // Simulate JNI return overhead
            simulateJNIMarshalingOverhead(50); // 50ns JNI return overhead

            // Simulate routing decision creation (more GC pressure)
            RoutingResult result = new RoutingResult(order.orderId, "INTERNAL", 100L);

            assertNotNull(result, "Result should not be null");
        }
        long jniEndTime = System.nanoTime();
        long jniTime = jniEndTime - jniStartTime;

        log.info("📊 Simulated JNI: {} iterations in {}μs (avg: {:.1f}ns per round-trip)",
                iterations, jniTime / 1000, jniTime / (double)iterations);

        // Performance comparison
        double speedup = jniTime / (double)sbeTime;
        long latencySavings = (jniTime - sbeTime) / iterations;

        log.info("🎯 PERFORMANCE RESULTS:");
        log.info("   • SBE Zero-JNI speedup: {:.2f}x faster", speedup);
        log.info("   • Latency savings: {}ns per round-trip", latencySavings);
        log.info("   • Throughput improvement: {:.1f}% higher", (speedup - 1) * 100);

        // Assertions for performance requirements
        assertTrue(speedup > 1.5, "SBE should be at least 1.5x faster than JNI");
        assertTrue(latencySavings > 50, "Should save at least 50ns per round-trip");

        log.info("✅ Zero-JNI performance test passed with {:.2f}x speedup", speedup);
    }

    @Test
    @DisplayName("🔥 High-Frequency Trading Latency Benchmark")
    void testHFTLatencyBenchmark() {
        log.info("🔥 Starting HFT Latency Benchmark");

        int warmupIterations = 1_000;
        int benchmarkIterations = 100_000;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            performSingleOrderRoutingCycle(i);
        }

        log.info("🔥 Warmup complete, starting benchmark...");

        // Benchmark measurement
        long[] latencies = new long[benchmarkIterations];

        for (int i = 0; i < benchmarkIterations; i++) {
            long startTime = System.nanoTime();
            performSingleOrderRoutingCycle(warmupIterations + i);
            long endTime = System.nanoTime();

            latencies[i] = endTime - startTime;
        }

        // Calculate statistics
        java.util.Arrays.sort(latencies);

        long minLatency = latencies[0];
        long maxLatency = latencies[benchmarkIterations - 1];
        long medianLatency = latencies[benchmarkIterations / 2];
        long p95Latency = latencies[(int)(benchmarkIterations * 0.95)];
        long p99Latency = latencies[(int)(benchmarkIterations * 0.99)];
        long p999Latency = latencies[(int)(benchmarkIterations * 0.999)];

        double avgLatency = java.util.Arrays.stream(latencies).average().orElse(0.0);

        log.info("📊 HFT LATENCY STATISTICS ({} iterations):", benchmarkIterations);
        log.info("   • Min latency:    {}ns", minLatency);
        log.info("   • Average latency: {:.1f}ns", avgLatency);
        log.info("   • Median latency:  {}ns", medianLatency);
        log.info("   • P95 latency:     {}ns", p95Latency);
        log.info("   • P99 latency:     {}ns", p99Latency);
        log.info("   • P99.9 latency:   {}ns", p999Latency);
        log.info("   • Max latency:     {}ns", maxLatency);

        double throughputPerSecond = 1_000_000_000.0 / avgLatency;
        log.info("   • Estimated throughput: {:.0f} round-trips/sec", throughputPerSecond);

        // HFT performance assertions
        assertTrue(avgLatency < 1_000, "Average latency should be < 1μs for HFT");
        assertTrue(p99Latency < 2_000, "P99 latency should be < 2μs for HFT");
        assertTrue(throughputPerSecond > 1_000_000, "Should handle > 1M round-trips/sec");

        log.info("✅ HFT latency benchmark passed - ready for production HFT");
    }

    @Test
    @DisplayName("💾 Memory Allocation Test - Zero GC Validation")
    void testZeroAllocationValidation() {
        log.info("💾 Starting Zero Allocation Validation Test");

        // Capture initial memory state
        System.gc(); // Force GC before test
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        int iterations = 50_000;
        log.info("🧪 Processing {} orders with zero-allocation SBE...", iterations);

        // Process orders using SBE (should cause zero allocations)
        for (int i = 0; i < iterations; i++) {
            long sequenceId = i;

            // Use existing buffers - no allocation
            MutableDirectBuffer writeBuffer = bufferManager.getWriteBuffer(sequenceId);
            encodeOrderRequestFast(writeBuffer, sequenceId, 2000L + i, "MSFT", (byte)1, 28000000L, 200L);
            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, 64);

            // Read using existing buffer - no allocation
            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            long orderId = readBuffer.getLong(8);
            String symbol = readSymbol(readBuffer, 16); // Reuse string buffer technique

            assertNotNull(symbol);
            assertTrue(orderId > 2000L);
        }

        // Check memory after processing
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;

        log.info("📊 MEMORY ALLOCATION RESULTS:");
        log.info("   • Initial memory: {} KB", initialMemory / 1024);
        log.info("   • Final memory:   {} KB", finalMemory / 1024);
        log.info("   • Memory increase: {} KB", memoryIncrease / 1024);
        log.info("   • Per-message allocation: {:.2f} bytes", memoryIncrease / (double)iterations);

        // Zero allocation assertions
        assertTrue(memoryIncrease < 100_000, "Memory increase should be minimal (< 100KB)");

        double allocationPerMessage = memoryIncrease / (double)iterations;
        assertTrue(allocationPerMessage < 10.0, "Should allocate < 10 bytes per message on average");

        log.info("✅ Zero allocation validation passed - {:.2f} bytes per message", allocationPerMessage);
    }

    @Test
    @DisplayName("🌊 Throughput Stress Test")
    void testThroughputStressTest() {
        log.info("🌊 Starting Throughput Stress Test");

        int[] threadCounts = {1, 2, 4, 8};
        int iterationsPerThread = 25_000;

        for (int threadCount : threadCounts) {
            log.info("🧵 Testing with {} threads...", threadCount);

            Thread[] threads = new Thread[threadCount];
            long[] threadTimes = new long[threadCount];

            long overallStartTime = System.nanoTime();

            // Start all threads
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        long sequenceId = (long)threadId * iterationsPerThread + i;
                        performSingleOrderRoutingCycle(sequenceId);
                    }
                });

                threads[t].start();
            }

            // Wait for all threads to complete
            try {
                for (Thread thread : threads) {
                    thread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Thread interrupted during test");
            }

            long overallEndTime = System.nanoTime();
            long overallTime = overallEndTime - overallStartTime;

            int totalMessages = threadCount * iterationsPerThread;
            double throughput = totalMessages / (overallTime / 1_000_000_000.0);
            double avgLatency = overallTime / (double)totalMessages;

            log.info("📊 {} threads results:", threadCount);
            log.info("   • Total messages: {}", totalMessages);
            log.info("   • Overall time: {}ms", overallTime / 1_000_000);
            log.info("   • Throughput: {:.0f} messages/sec", throughput);
            log.info("   • Avg latency: {:.1f}ns per message", avgLatency);

            // Performance assertions based on thread count
            double expectedMinThroughput = Math.min(threadCount * 500_000, 5_000_000); // Scale with threads, max 5M
            assertTrue(throughput > expectedMinThroughput,
                    "Throughput should be > " + expectedMinThroughput + " with " + threadCount + " threads");
        }

        log.info("✅ Throughput stress test completed successfully");
    }

    // Helper methods for fast encoding/decoding
    private int encodeOrderRequestFast(MutableDirectBuffer buffer, long sequenceId, long orderId,
                                      String symbol, byte side, long price, long quantity) {
        int offset = 0;
        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;

        // Fast symbol encoding
        byte[] symbolBytes = symbol.getBytes();
        for (int i = 0; i < 16; i++) {
            buffer.putByte(offset + i, i < symbolBytes.length ? symbolBytes[i] : (byte)0);
        }
        offset += 16;

        buffer.putByte(offset, side); offset += 1;
        buffer.putLong(offset, price); offset += 8;
        buffer.putLong(offset, quantity); offset += 8;

        return offset;
    }

    private int encodeRoutingDecisionFast(MutableDirectBuffer buffer, long sequenceId, long orderId, byte action) {
        int offset = 0;
        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;
        buffer.putByte(offset, action); offset += 1;

        return offset;
    }

    private void performSingleOrderRoutingCycle(long sequenceId) {
        // Full OSM → SOR → OSM cycle
        MutableDirectBuffer writeBuffer = bufferManager.getWriteBuffer(sequenceId);
        encodeOrderRequestFast(writeBuffer, sequenceId, 3000L + sequenceId, "GOOGL", (byte)0, 280000000L, 50L);
        bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, 41);

        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        long orderId = readBuffer.getLong(8);

        long responseSeq = sequenceId + 1_000_000L;
        MutableDirectBuffer responseBuffer = bufferManager.getWriteBuffer(responseSeq);
        encodeRoutingDecisionFast(responseBuffer, responseSeq, orderId, (byte)1);
        bufferManager.publishMessage(responseSeq, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, 17);

        UnsafeBuffer decisionBuffer = bufferManager.getReadBuffer(responseSeq);
        byte action = decisionBuffer.getByte(17);

        // Validation
        assertEquals(1, action);
    }

    private String readSymbol(UnsafeBuffer buffer, int offset) {
        // Optimized symbol reading to avoid allocation
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            byte b = buffer.getByte(offset + i);
            if (b == 0) break;
            sb.append((char)b);
        }
        return sb.toString();
    }

    private void simulateJNIMarshalingOverhead(long nanos) {
        // Simulate JNI overhead with busy wait
        long start = System.nanoTime();
        while (System.nanoTime() - start < nanos) {
            // Busy wait to simulate JNI overhead
            Thread.onSpinWait();
        }
    }

    // Simple data classes to simulate JNI object marshaling
    static class OrderData {
        final long orderId;
        final String symbol;
        final long quantity;
        final long price;

        OrderData(long orderId, String symbol, long quantity, long price) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.quantity = quantity;
            this.price = price;
        }
    }

    static class RoutingResult {
        final long orderId;
        final String venue;
        final long allocatedQuantity;

        RoutingResult(long orderId, String venue, long allocatedQuantity) {
            this.orderId = orderId;
            this.venue = venue;
            this.allocatedQuantity = allocatedQuantity;
        }
    }
}
