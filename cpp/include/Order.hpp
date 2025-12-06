#ifndef ORDER_HPP
#define ORDER_HPP

#include <string>
#include <chrono>
#include <cstdint>

namespace MicroOptimus {

enum class OrderSide {
    BUY,
    SELL
};

enum class OrderType {
    LIMIT,
    MARKET,
    IOC,  // Immediate or Cancel
    FOK   // Fill or Kill
};

enum class OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED
};

class Order {
public:
    uint64_t orderId;
    uint64_t sequenceNumber;
    std::string symbol;
    OrderSide side;
    OrderType type;
    OrderStatus status;
    double price;
    uint64_t quantity;
    uint64_t filledQuantity;
    std::chrono::system_clock::time_point timestamp;
    std::string clientId;

    Order(uint64_t id, const std::string& sym, OrderSide s, OrderType t,
          double p, uint64_t qty, const std::string& client)
        : orderId(id), sequenceNumber(0), symbol(sym), side(s), type(t),
          status(OrderStatus::NEW), price(p), quantity(qty),
          filledQuantity(0), clientId(client) {
        timestamp = std::chrono::system_clock::now();
    }

    uint64_t remainingQuantity() const {
        return quantity - filledQuantity;
    }

    bool isComplete() const {
        return status == OrderStatus::FILLED || 
               status == OrderStatus::CANCELLED ||
               status == OrderStatus::REJECTED;
    }
};

} // namespace MicroOptimus

#endif // ORDER_HPP
