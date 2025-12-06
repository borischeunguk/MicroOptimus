#ifndef ORDERBOOK_HPP
#define ORDERBOOK_HPP

#include "Order.hpp"
#include <map>
#include <list>
#include <memory>
#include <mutex>
#include <vector>

namespace MicroOptimus {

// Price level containing orders at the same price
class PriceLevel {
public:
    double price;
    std::list<std::shared_ptr<Order>> orders;
    uint64_t totalQuantity;

    explicit PriceLevel(double p) : price(p), totalQuantity(0) {}

    void addOrder(std::shared_ptr<Order> order) {
        orders.push_back(order);
        totalQuantity += order->remainingQuantity();
    }

    void removeOrder(uint64_t orderId) {
        for (auto it = orders.begin(); it != orders.end(); ++it) {
            if ((*it)->orderId == orderId) {
                totalQuantity -= (*it)->remainingQuantity();
                orders.erase(it);
                break;
            }
        }
    }

    void updateQuantity(uint64_t orderId, uint64_t newRemaining) {
        for (auto& order : orders) {
            if (order->orderId == orderId) {
                totalQuantity = totalQuantity - order->remainingQuantity() + newRemaining;
                break;
            }
        }
    }
};

// Sequencer-based orderbook with price-time priority
class OrderBook {
private:
    std::string symbol_;
    uint64_t nextSequenceNumber_;
    
    // Buy orders: higher prices first
    std::map<double, PriceLevel, std::greater<double>> buyLevels_;
    
    // Sell orders: lower prices first
    std::map<double, PriceLevel, std::less<double>> sellLevels_;
    
    // Order lookup by ID
    std::map<uint64_t, std::shared_ptr<Order>> orders_;
    
    mutable std::mutex mutex_;

public:
    explicit OrderBook(const std::string& symbol)
        : symbol_(symbol), nextSequenceNumber_(1) {}

    // Add order to the book with sequence number
    bool addOrder(std::shared_ptr<Order> order);

    // Cancel an order
    bool cancelOrder(uint64_t orderId);

    // Modify an order (price or quantity)
    bool modifyOrder(uint64_t orderId, double newPrice, uint64_t newQuantity);

    // Get best bid price
    double getBestBid() const;

    // Get best ask price
    double getBestAsk() const;

    // Get mid price
    double getMidPrice() const;

    // Get order by ID
    std::shared_ptr<Order> getOrder(uint64_t orderId) const;

    // Get all buy orders at a price level
    std::vector<std::shared_ptr<Order>> getBuyOrdersAtPrice(double price) const;

    // Get all sell orders at a price level
    std::vector<std::shared_ptr<Order>> getSellOrdersAtPrice(double price) const;

    // Get market depth (top N levels)
    struct DepthLevel {
        double price;
        uint64_t quantity;
    };
    std::vector<DepthLevel> getBidDepth(size_t levels) const;
    std::vector<DepthLevel> getAskDepth(size_t levels) const;

    // Get symbol
    const std::string& getSymbol() const { return symbol_; }

private:
    void assignSequenceNumber(std::shared_ptr<Order> order);
};

} // namespace MicroOptimus

#endif // ORDERBOOK_HPP
