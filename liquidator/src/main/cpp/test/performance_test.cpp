// Performance test for C++ Smart Order Router
#include "../sor/smart_order_router_simple.cpp"
#include <iostream>
#include <chrono>
#include <vector>
#include <numeric>
#include <algorithm>

using namespace microoptimus::sor;
using namespace std::chrono;

int main() {
    std::cout << "=== C++ Smart Order Router Performance Test ===" << std::endl;

    // Initialize SOR
    auto sor = std::make_unique<SmartOrderRouter>();
    int initResult = sor->initialize("/tmp/sor_test.conf", "/tmp/sor_shared_memory.bin");

    if (initResult != 0) {
        std::cerr << "Failed to initialize SOR: " << initResult << std::endl;
        return 1;
    }

    std::cout << "SOR initialized successfully" << std::endl;

    // Test parameters
    const int warmupRuns = 1000;
    const int testRuns = 100000;

    // Create test orders
    std::vector<OrderRequest> testOrders;
    testOrders.reserve(warmupRuns + testRuns);

    std::vector<std::string> symbols = {"AAPL", "GOOGL", "MSFT", "TSLA", "AMZN"};

    for (int i = 0; i < warmupRuns + testRuns; i++) {
        testOrders.emplace_back(
            i + 1,                                    // orderId
            symbols[i % symbols.size()],              // symbol
            (i % 2 == 0) ? Side::BUY : Side::SELL,   // side
            OrderType::LIMIT,                         // orderType
            10000 + (i % 5000),                      // price
            100 + (i % 900),                         // quantity
            duration_cast<nanoseconds>(high_resolution_clock::now().time_since_epoch()).count()
        );
    }

    // Warmup
    std::cout << "Warming up with " << warmupRuns << " orders..." << std::endl;
    for (int i = 0; i < warmupRuns; i++) {
        sor->routeOrder(testOrders[i]);
    }

    // Performance measurement
    std::cout << "Performance test with " << testRuns << " orders..." << std::endl;

    std::vector<long> latencies;
    latencies.reserve(testRuns);

    auto overallStart = high_resolution_clock::now();

    for (int i = warmupRuns; i < warmupRuns + testRuns; i++) {
        auto start = high_resolution_clock::now();
        RoutingDecision decision = sor->routeOrder(testOrders[i]);
        auto end = high_resolution_clock::now();

        long latency = duration_cast<nanoseconds>(end - start).count();
        latencies.push_back(latency);
    }

    auto overallEnd = high_resolution_clock::now();
    long totalTime = duration_cast<nanoseconds>(overallEnd - overallStart).count();

    // Calculate statistics
    std::sort(latencies.begin(), latencies.end());

    long minLatency = latencies.front();
    long maxLatency = latencies.back();
    double avgLatency = std::accumulate(latencies.begin(), latencies.end(), 0.0) / testRuns;
    long medianLatency = latencies[testRuns / 2];
    long p95Latency = latencies[(int)(testRuns * 0.95)];
    long p99Latency = latencies[(int)(testRuns * 0.99)];

    double throughput = (double)testRuns / (totalTime / 1e9);

    // Print results
    std::cout << "\n=== Performance Results ===" << std::endl;
    std::cout << "Test runs: " << testRuns << std::endl;
    std::cout << "Total time: " << (totalTime / 1e6) << " ms" << std::endl;
    std::cout << "Throughput: " << (long)throughput << " orders/sec" << std::endl;
    std::cout << "\nLatency Statistics (nanoseconds):" << std::endl;
    std::cout << "  Min:    " << minLatency << " ns" << std::endl;
    std::cout << "  Avg:    " << (long)avgLatency << " ns" << std::endl;
    std::cout << "  Median: " << medianLatency << " ns" << std::endl;
    std::cout << "  P95:    " << p95Latency << " ns" << std::endl;
    std::cout << "  P99:    " << p99Latency << " ns" << std::endl;
    std::cout << "  Max:    " << maxLatency << " ns" << std::endl;

    // Get SOR statistics
    int64_t stats[7];
    sor->getStatistics(stats);

    std::cout << "\n=== SOR Internal Statistics ===" << std::endl;
    std::cout << "Total orders: " << stats[0] << std::endl;
    std::cout << "Internal routes: " << stats[1] << std::endl;
    std::cout << "External routes: " << stats[2] << std::endl;
    std::cout << "Rejected orders: " << stats[3] << std::endl;
    std::cout << "Avg latency: " << stats[4] << " ns" << std::endl;
    std::cout << "Max latency: " << stats[5] << " ns" << std::endl;
    std::cout << "Min latency: " << stats[6] << " ns" << std::endl;

    // Performance validation
    bool passed = true;
    std::cout << "\n=== Performance Validation ===" << std::endl;

    if (avgLatency < 500) {
        std::cout << "✅ Average latency target met: " << (long)avgLatency << "ns < 500ns" << std::endl;
    } else {
        std::cout << "❌ Average latency target missed: " << (long)avgLatency << "ns >= 500ns" << std::endl;
        passed = false;
    }

    if (p99Latency < 2000) {
        std::cout << "✅ P99 latency target met: " << p99Latency << "ns < 2000ns" << std::endl;
    } else {
        std::cout << "❌ P99 latency target missed: " << p99Latency << "ns >= 2000ns" << std::endl;
        passed = false;
    }

    if (throughput > 1000000) {
        std::cout << "✅ Throughput target met: " << (long)throughput << " orders/sec > 1M orders/sec" << std::endl;
    } else {
        std::cout << "❌ Throughput target missed: " << (long)throughput << " orders/sec < 1M orders/sec" << std::endl;
        passed = false;
    }

    // Cleanup
    sor->shutdown();

    std::cout << "\n=== Test " << (passed ? "PASSED" : "FAILED") << " ===" << std::endl;

    return passed ? 0 : 1;
}
