/**
 * Unit tests for Boost circular_buffer
 * Tests basic functionality and performance characteristics
 */

#include <gtest/gtest.h>
#include <boost/circular_buffer.hpp>
#include <vector>
#include <string>
#include <chrono>

/**
 * Test fixture for circular_buffer tests
 */
class BoostCircularBufferTest : public ::testing::Test {
protected:
    void SetUp() override {
        // Setup code if needed
    }

    void TearDown() override {
        // Cleanup code if needed
    }
};

/**
 * Test basic construction and capacity
 */
TEST_F(BoostCircularBufferTest, ConstructionAndCapacity) {
    boost::circular_buffer<int> buffer(5);

    EXPECT_EQ(buffer.capacity(), 5);
    EXPECT_EQ(buffer.size(), 0);
    EXPECT_TRUE(buffer.empty());
    EXPECT_FALSE(buffer.full());
}

/**
 * Test push_back and element access
 */
TEST_F(BoostCircularBufferTest, PushBackAndAccess) {
    boost::circular_buffer<int> buffer(3);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);

    EXPECT_EQ(buffer.size(), 3);
    EXPECT_TRUE(buffer.full());
    EXPECT_EQ(buffer[0], 1);
    EXPECT_EQ(buffer[1], 2);
    EXPECT_EQ(buffer[2], 3);
    EXPECT_EQ(buffer.front(), 1);
    EXPECT_EQ(buffer.back(), 3);
}

/**
 * Test circular overwrite behavior
 */
TEST_F(BoostCircularBufferTest, CircularOverwrite) {
    boost::circular_buffer<int> buffer(3);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);
    buffer.push_back(4); // Overwrites 1

    EXPECT_EQ(buffer.size(), 3);
    EXPECT_TRUE(buffer.full());
    EXPECT_EQ(buffer[0], 2);
    EXPECT_EQ(buffer[1], 3);
    EXPECT_EQ(buffer[2], 4);
    EXPECT_EQ(buffer.front(), 2);
    EXPECT_EQ(buffer.back(), 4);
}

/**
 * Test push_front
 */
TEST_F(BoostCircularBufferTest, PushFront) {
    boost::circular_buffer<int> buffer(3);

    buffer.push_front(1);
    buffer.push_front(2);
    buffer.push_front(3);

    EXPECT_EQ(buffer.size(), 3);
    EXPECT_EQ(buffer[0], 3);
    EXPECT_EQ(buffer[1], 2);
    EXPECT_EQ(buffer[2], 1);
}

/**
 * Test pop_back and pop_front
 */
TEST_F(BoostCircularBufferTest, PopOperations) {
    boost::circular_buffer<int> buffer(5);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);

    buffer.pop_back();
    EXPECT_EQ(buffer.size(), 2);
    EXPECT_EQ(buffer.back(), 2);

    buffer.pop_front();
    EXPECT_EQ(buffer.size(), 1);
    EXPECT_EQ(buffer.front(), 2);
}

/**
 * Test iterator functionality
 */
TEST_F(BoostCircularBufferTest, Iterators) {
    boost::circular_buffer<int> buffer(5);

    buffer.push_back(10);
    buffer.push_back(20);
    buffer.push_back(30);

    // Test forward iteration
    std::vector<int> values;
    for (auto it = buffer.begin(); it != buffer.end(); ++it) {
        values.push_back(*it);
    }

    ASSERT_EQ(values.size(), 3);
    EXPECT_EQ(values[0], 10);
    EXPECT_EQ(values[1], 20);
    EXPECT_EQ(values[2], 30);

    // Test range-based for loop
    values.clear();
    for (int val : buffer) {
        values.push_back(val);
    }

    ASSERT_EQ(values.size(), 3);
    EXPECT_EQ(values[0], 10);
    EXPECT_EQ(values[1], 20);
    EXPECT_EQ(values[2], 30);
}

/**
 * Test clear operation
 */
TEST_F(BoostCircularBufferTest, Clear) {
    boost::circular_buffer<int> buffer(5);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);

    buffer.clear();

    EXPECT_EQ(buffer.size(), 0);
    EXPECT_TRUE(buffer.empty());
    EXPECT_EQ(buffer.capacity(), 5); // Capacity unchanged
}

/**
 * Test with custom types (struct)
 */
TEST_F(BoostCircularBufferTest, CustomTypes) {
    struct Order {
        int id;
        double price;
        int quantity;

        Order(int i, double p, int q) : id(i), price(p), quantity(q) {}

        bool operator==(const Order& other) const {
            return id == other.id && price == other.price && quantity == other.quantity;
        }
    };

    boost::circular_buffer<Order> buffer(3);

    buffer.push_back(Order(1, 100.5, 100));
    buffer.push_back(Order(2, 101.0, 200));
    buffer.push_back(Order(3, 99.5, 150));

    EXPECT_EQ(buffer.size(), 3);
    EXPECT_EQ(buffer[0].id, 1);
    EXPECT_EQ(buffer[1].price, 101.0);
    EXPECT_EQ(buffer[2].quantity, 150);

    // Test overwrite
    buffer.push_back(Order(4, 102.0, 300));

    EXPECT_EQ(buffer.size(), 3);
    EXPECT_EQ(buffer[0].id, 2); // First element was overwritten
    EXPECT_EQ(buffer[2].id, 4);
}

/**
 * Test linearize functionality (converts to contiguous array)
 */
TEST_F(BoostCircularBufferTest, Linearize) {
    boost::circular_buffer<int> buffer(5);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);
    buffer.push_back(4);
    buffer.push_back(5);
    buffer.push_back(6); // Overwrites 1, buffer wraps

    // Linearize makes the buffer contiguous
    int* array = buffer.linearize();

    EXPECT_EQ(array[0], 2);
    EXPECT_EQ(array[1], 3);
    EXPECT_EQ(array[2], 4);
    EXPECT_EQ(array[3], 5);
    EXPECT_EQ(array[4], 6);
}

/**
 * Test resize functionality
 */
TEST_F(BoostCircularBufferTest, Resize) {
    boost::circular_buffer<int> buffer(3);

    buffer.push_back(1);
    buffer.push_back(2);
    buffer.push_back(3);

    // Resize to larger capacity
    buffer.set_capacity(5);

    EXPECT_EQ(buffer.capacity(), 5);
    EXPECT_EQ(buffer.size(), 3);
    EXPECT_FALSE(buffer.full());

    // Resize to smaller capacity (should truncate)
    buffer.set_capacity(2);

    EXPECT_EQ(buffer.capacity(), 2);
    EXPECT_EQ(buffer.size(), 2);
    EXPECT_TRUE(buffer.full());
}

/**
 * Test with pointers (for low-latency scenarios)
 */
TEST_F(BoostCircularBufferTest, PointerTypes) {
    boost::circular_buffer<int*> buffer(3);

    int a = 10, b = 20, c = 30;

    buffer.push_back(&a);
    buffer.push_back(&b);
    buffer.push_back(&c);

    EXPECT_EQ(*buffer[0], 10);
    EXPECT_EQ(*buffer[1], 20);
    EXPECT_EQ(*buffer[2], 30);
}

/**
 * Performance test: measure insertion throughput
 */
TEST_F(BoostCircularBufferTest, PerformanceInsertion) {
    const size_t buffer_size = 1000;
    const size_t num_operations = 1000000;

    boost::circular_buffer<int> buffer(buffer_size);

    auto start = std::chrono::high_resolution_clock::now();

    for (size_t i = 0; i < num_operations; ++i) {
        buffer.push_back(static_cast<int>(i));
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::nanoseconds>(end - start);

    double avg_latency_ns = static_cast<double>(duration.count()) / num_operations;
    double throughput = num_operations / (duration.count() / 1e9);

    std::cout << "Performance Results:\n"
              << "  Operations: " << num_operations << "\n"
              << "  Total time: " << duration.count() / 1e6 << " ms\n"
              << "  Avg latency: " << avg_latency_ns << " ns/op\n"
              << "  Throughput: " << throughput / 1e6 << " M ops/sec\n";

    // Reasonable performance expectations for circular buffer
    EXPECT_LT(avg_latency_ns, 100); // Less than 100ns per operation
}

/**
 * Test edge case: empty buffer pop
 */
TEST_F(BoostCircularBufferTest, EdgeCaseEmptyPop) {
    boost::circular_buffer<int> buffer(5);

    // Note: pop_back/pop_front on empty buffer is undefined behavior
    // We just test that the buffer is empty
    EXPECT_TRUE(buffer.empty());
    EXPECT_EQ(buffer.size(), 0);
}

/**
 * Test edge case: single element buffer
 */
TEST_F(BoostCircularBufferTest, EdgeCaseSingleElement) {
    boost::circular_buffer<int> buffer(1);

    buffer.push_back(42);
    EXPECT_TRUE(buffer.full());
    EXPECT_EQ(buffer.front(), 42);
    EXPECT_EQ(buffer.back(), 42);

    buffer.push_back(100);
    EXPECT_EQ(buffer.front(), 100);
    EXPECT_EQ(buffer.back(), 100);
}

/**
 * Test with string types
 */
TEST_F(BoostCircularBufferTest, StringTypes) {
    boost::circular_buffer<std::string> buffer(3);

    buffer.push_back("AAPL");
    buffer.push_back("GOOGL");
    buffer.push_back("MSFT");

    EXPECT_EQ(buffer[0], "AAPL");
    EXPECT_EQ(buffer[1], "GOOGL");
    EXPECT_EQ(buffer[2], "MSFT");

    buffer.push_back("TSLA");

    EXPECT_EQ(buffer[0], "GOOGL");
    EXPECT_EQ(buffer[2], "TSLA");
}

/**
 * Main function for standalone test execution
 */
int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}

