/**
 * C++ test that reads SBE-encoded OrderRequest from shared memory
 * written by Java test (JavaToSharedMemoryTest.java)
 *
 * This demonstrates zero-copy, zero-JNI communication via shared memory + SBE
 */

#include <iostream>
#include <fstream>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <cassert>

// SBE generated headers (will be generated from OrderRequestMessage.xml)
// For now, we'll manually decode to demonstrate the pattern
// In production, run: sbe-tool OrderRequestMessage.xml

namespace sbe_test {

// Manual SBE decoding structures (simplified version)
// In production, use SBE-generated C++ code

#pragma pack(push, 1)

struct MessageHeader {
    uint16_t blockLength;
    uint16_t templateId;
    uint16_t schemaId;
    uint16_t version;
};

enum class Side : uint8_t {
    BUY = 0,
    SELL = 1
};

enum class OrderType : uint8_t {
    MARKET = 0,
    LIMIT = 1,
    STOP = 2,
    STOP_LIMIT = 3
};

enum class Algorithm : uint8_t {
    SIMPLE = 1,
    TWAP = 2,
    VWAP = 3,
    POV = 4,
    ICEBERG = 5
};

// OrderRequest message layout (matches SBE schema)
struct OrderRequestWire {
    uint64_t sequenceId;      // offset 0
    uint64_t orderId;         // offset 8
    char symbol[16];          // offset 16
    Side side;                // offset 32
    OrderType orderType;      // offset 33
    uint64_t price;           // offset 34
    uint64_t quantity;        // offset 42
    uint64_t timestamp;       // offset 50
    Algorithm algorithm;      // offset 58
    uint64_t maxLatencyNanos; // offset 59
    uint32_t clientId;        // offset 67
    uint64_t minFillQty;      // offset 71
    uint8_t timeInForce;      // offset 79
};

#pragma pack(pop)

class SharedMemoryReader {
private:
    void* mappedMemory;
    size_t size;
    int fd;

public:
    SharedMemoryReader(const char* path, size_t mapSize) : size(mapSize) {
        fd = open(path, O_RDONLY);
        if (fd == -1) {
            throw std::runtime_error("Failed to open shared memory file");
        }

        mappedMemory = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
        if (mappedMemory == MAP_FAILED) {
            close(fd);
            throw std::runtime_error("Failed to mmap shared memory");
        }

        std::cout << "Mapped " << size << " bytes from " << path << std::endl;
    }

    ~SharedMemoryReader() {
        if (mappedMemory != MAP_FAILED) {
            munmap(mappedMemory, size);
        }
        if (fd != -1) {
            close(fd);
        }
    }

    void* getBuffer() { return mappedMemory; }

    MessageHeader readHeader(int offset = 0) {
        MessageHeader header;
        std::memcpy(&header, static_cast<char*>(mappedMemory) + offset, sizeof(MessageHeader));
        return header;
    }

    OrderRequestWire readOrderRequest(int offset = sizeof(MessageHeader)) {
        OrderRequestWire order;
        std::memcpy(&order, static_cast<char*>(mappedMemory) + offset, sizeof(OrderRequestWire));
        return order;
    }
};

class RingBufferReader {
private:
    void* mappedMemory;

public:
    RingBufferReader(void* buffer) : mappedMemory(buffer) {}

    uint64_t getWritePosition() {
        return *static_cast<uint64_t*>(mappedMemory);
    }

    uint64_t getReadPosition() {
        return *reinterpret_cast<uint64_t*>(static_cast<char*>(mappedMemory) + 8);
    }

    uint64_t getSequence() {
        return *reinterpret_cast<uint64_t*>(static_cast<char*>(mappedMemory) + 16);
    }

    void* getMessageBuffer(int offset) {
        return static_cast<char*>(mappedMemory) + offset;
    }
};

void printOrderRequest(const OrderRequestWire& order) {
    std::cout << "=== OrderRequest Details ===" << std::endl;
    std::cout << "SequenceId:  " << order.sequenceId << std::endl;
    std::cout << "OrderId:     " << order.orderId << std::endl;

    char symbol[17] = {0};
    std::strncpy(symbol, order.symbol, 16);
    std::cout << "Symbol:      " << symbol << std::endl;

    std::cout << "Side:        " << (order.side == Side::BUY ? "BUY" : "SELL") << std::endl;
    std::cout << "OrderType:   ";
    switch (order.orderType) {
        case OrderType::MARKET: std::cout << "MARKET"; break;
        case OrderType::LIMIT: std::cout << "LIMIT"; break;
        case OrderType::STOP: std::cout << "STOP"; break;
        case OrderType::STOP_LIMIT: std::cout << "STOP_LIMIT"; break;
    }
    std::cout << std::endl;

    std::cout << "Price:       $" << (order.price / 1000000.0) << std::endl;
    std::cout << "Quantity:    " << order.quantity << std::endl;
    std::cout << "Timestamp:   " << order.timestamp << std::endl;

    std::cout << "Algorithm:   ";
    switch (order.algorithm) {
        case Algorithm::SIMPLE: std::cout << "SIMPLE"; break;
        case Algorithm::TWAP: std::cout << "TWAP"; break;
        case Algorithm::VWAP: std::cout << "VWAP"; break;
        case Algorithm::POV: std::cout << "POV"; break;
        case Algorithm::ICEBERG: std::cout << "ICEBERG"; break;
    }
    std::cout << std::endl;

    std::cout << "MaxLatency:  " << order.maxLatencyNanos << " ns" << std::endl;
    std::cout << "ClientId:    " << order.clientId << std::endl;
    std::cout << "MinFillQty:  " << order.minFillQty << std::endl;
    std::cout << "TimeInForce: " << (int)order.timeInForce << std::endl;
}

} // namespace sbe_test

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "Usage: " << argv[0] << " <shared_memory_file> [ring|single]" << std::endl;
        std::cerr << "Example: " << argv[0] << " /tmp/order_request.shm single" << std::endl;
        std::cerr << "         " << argv[0] << " /tmp/order_ring_buffer.shm ring" << std::endl;
        return 1;
    }

    const char* shmPath = argv[1];
    bool isRingBuffer = (argc > 2 && std::string(argv[2]) == "ring");

    try {
        size_t mapSize = isRingBuffer ? 64 * 1024 : 1024 * 1024;
        sbe_test::SharedMemoryReader reader(shmPath, mapSize);

        std::cout << "\n=== C++ SBE Shared Memory Reader ===" << std::endl;
        std::cout << "File: " << shmPath << std::endl;
        std::cout << "Mode: " << (isRingBuffer ? "Ring Buffer" : "Single Message") << std::endl;
        std::cout << std::endl;

        if (isRingBuffer) {
            // Read ring buffer
            sbe_test::RingBufferReader ringReader(reader.getBuffer());

            uint64_t writePos = ringReader.getWritePosition();
            uint64_t readPos = ringReader.getReadPosition();
            uint64_t sequence = ringReader.getSequence();

            std::cout << "=== Ring Buffer Header ===" << std::endl;
            std::cout << "Write Position: " << writePos << std::endl;
            std::cout << "Read Position:  " << readPos << std::endl;
            std::cout << "Sequence:       " << sequence << std::endl;
            std::cout << std::endl;

            // Read messages from offset 32 (header size)
            int currentOffset = 32;
            int orderCount = 0;

            while (currentOffset < 32 + static_cast<int>(writePos)) {
                sbe_test::MessageHeader header = reader.readHeader(currentOffset);

                std::cout << "\n--- Message " << orderCount << " ---" << std::endl;
                std::cout << "Offset:       " << currentOffset << std::endl;
                std::cout << "TemplateId:   " << header.templateId << std::endl;
                std::cout << "BlockLength:  " << header.blockLength << std::endl;
                std::cout << "Version:      " << header.version << std::endl;

                sbe_test::OrderRequestWire order = reader.readOrderRequest(
                    currentOffset + sizeof(sbe_test::MessageHeader));
                sbe_test::printOrderRequest(order);

                currentOffset += sizeof(sbe_test::MessageHeader) + header.blockLength;
                orderCount++;
            }

            std::cout << "\nTotal orders read: " << orderCount << std::endl;

        } else {
            // Read single message
            sbe_test::MessageHeader header = reader.readHeader(0);

            std::cout << "=== Message Header ===" << std::endl;
            std::cout << "TemplateId:  " << header.templateId << std::endl;
            std::cout << "BlockLength: " << header.blockLength << std::endl;
            std::cout << "SchemaId:    " << header.schemaId << std::endl;
            std::cout << "Version:     " << header.version << std::endl;
            std::cout << std::endl;

            sbe_test::OrderRequestWire order = reader.readOrderRequest();
            sbe_test::printOrderRequest(order);

            // Verify expected values from Java test
            assert(order.sequenceId == 12345);
            assert(order.orderId == 99999);
            assert(std::strncmp(order.symbol, "AAPL", 4) == 0);
            assert(order.side == sbe_test::Side::BUY);
            assert(order.orderType == sbe_test::OrderType::LIMIT);
            assert(order.price == 150500000);
            assert(order.quantity == 25000);
            assert(order.algorithm == sbe_test::Algorithm::VWAP);
            assert(order.clientId == 42);

            std::cout << "\n✅ All assertions passed!" << std::endl;
        }

        std::cout << "\n✅ Successfully read SBE message from shared memory" << std::endl;

    } catch (const std::exception& e) {
        std::cerr << "❌ Error: " << e.what() << std::endl;
        return 1;
    }

    return 0;
}

