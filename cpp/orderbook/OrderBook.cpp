#include "../include/OrderBook.hpp"
#include <algorithm>

namespace MicroOptimus {

void OrderBook::assignSequenceNumber(std::shared_ptr<Order> order) {
    order->sequenceNumber = nextSequenceNumber_++;
}

bool OrderBook::addOrder(std::shared_ptr<Order> order) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (order->symbol != symbol_) {
        return false;
    }

    // Assign sequence number for deterministic ordering
    assignSequenceNumber(order);

    // Store order
    orders_[order->orderId] = order;

    // Add to appropriate side
    if (order->side == OrderSide::BUY) {
        auto it = buyLevels_.find(order->price);
        if (it == buyLevels_.end()) {
            buyLevels_.emplace(order->price, PriceLevel(order->price));
            it = buyLevels_.find(order->price);
        }
        it->second.addOrder(order);
    } else {
        auto it = sellLevels_.find(order->price);
        if (it == sellLevels_.end()) {
            sellLevels_.emplace(order->price, PriceLevel(order->price));
            it = sellLevels_.find(order->price);
        }
        it->second.addOrder(order);
    }

    return true;
}

bool OrderBook::cancelOrder(uint64_t orderId) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = orders_.find(orderId);
    if (it == orders_.end()) {
        return false;
    }

    auto order = it->second;
    order->status = OrderStatus::CANCELLED;

    // Remove from price level
    if (order->side == OrderSide::BUY) {
        auto levelIt = buyLevels_.find(order->price);
        if (levelIt != buyLevels_.end()) {
            levelIt->second.removeOrder(orderId);
            if (levelIt->second.orders.empty()) {
                buyLevels_.erase(levelIt);
            }
        }
    } else {
        auto levelIt = sellLevels_.find(order->price);
        if (levelIt != sellLevels_.end()) {
            levelIt->second.removeOrder(orderId);
            if (levelIt->second.orders.empty()) {
                sellLevels_.erase(levelIt);
            }
        }
    }

    orders_.erase(it);
    return true;
}

bool OrderBook::modifyOrder(uint64_t orderId, double newPrice, uint64_t newQuantity) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = orders_.find(orderId);
    if (it == orders_.end()) {
        return false;
    }

    auto order = it->second;
    
    // If price changed, need to move to different level
    if (order->price != newPrice) {
        // Remove from old level
        if (order->side == OrderSide::BUY) {
            auto levelIt = buyLevels_.find(order->price);
            if (levelIt != buyLevels_.end()) {
                levelIt->second.removeOrder(orderId);
                if (levelIt->second.orders.empty()) {
                    buyLevels_.erase(levelIt);
                }
            }
        } else {
            auto levelIt = sellLevels_.find(order->price);
            if (levelIt != sellLevels_.end()) {
                levelIt->second.removeOrder(orderId);
                if (levelIt->second.orders.empty()) {
                    sellLevels_.erase(levelIt);
                }
            }
        }

        // Update order
        order->price = newPrice;
        order->quantity = newQuantity;
        assignSequenceNumber(order); // Re-sequence for price-time priority

        // Add to new level
        if (order->side == OrderSide::BUY) {
            auto levelIt = buyLevels_.find(newPrice);
            if (levelIt == buyLevels_.end()) {
                buyLevels_.emplace(newPrice, PriceLevel(newPrice));
                levelIt = buyLevels_.find(newPrice);
            }
            levelIt->second.addOrder(order);
        } else {
            auto levelIt = sellLevels_.find(newPrice);
            if (levelIt == sellLevels_.end()) {
                sellLevels_.emplace(newPrice, PriceLevel(newPrice));
                levelIt = sellLevels_.find(newPrice);
            }
            levelIt->second.addOrder(order);
        }
    } else {
        // Just update quantity
        order->quantity = newQuantity;
        if (order->side == OrderSide::BUY) {
            auto levelIt = buyLevels_.find(order->price);
            if (levelIt != buyLevels_.end()) {
                levelIt->second.updateQuantity(orderId, order->remainingQuantity());
            }
        } else {
            auto levelIt = sellLevels_.find(order->price);
            if (levelIt != sellLevels_.end()) {
                levelIt->second.updateQuantity(orderId, order->remainingQuantity());
            }
        }
    }

    return true;
}

double OrderBook::getBestBid() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (buyLevels_.empty()) {
        return 0.0;
    }
    return buyLevels_.begin()->first;
}

double OrderBook::getBestAsk() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sellLevels_.empty()) {
        return 0.0;
    }
    return sellLevels_.begin()->first;
}

double OrderBook::getMidPrice() const {
    double bid = getBestBid();
    double ask = getBestAsk();
    if (bid > 0.0 && ask > 0.0) {
        return (bid + ask) / 2.0;
    }
    return 0.0;
}

std::shared_ptr<Order> OrderBook::getOrder(uint64_t orderId) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = orders_.find(orderId);
    if (it != orders_.end()) {
        return it->second;
    }
    return nullptr;
}

std::vector<std::shared_ptr<Order>> OrderBook::getBuyOrdersAtPrice(double price) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::shared_ptr<Order>> result;
    auto it = buyLevels_.find(price);
    if (it != buyLevels_.end()) {
        result.insert(result.end(), it->second.orders.begin(), it->second.orders.end());
    }
    return result;
}

std::vector<std::shared_ptr<Order>> OrderBook::getSellOrdersAtPrice(double price) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::shared_ptr<Order>> result;
    auto it = sellLevels_.find(price);
    if (it != sellLevels_.end()) {
        result.insert(result.end(), it->second.orders.begin(), it->second.orders.end());
    }
    return result;
}

std::vector<OrderBook::DepthLevel> OrderBook::getBidDepth(size_t levels) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<DepthLevel> depth;
    size_t count = 0;
    for (const auto& level : buyLevels_) {
        if (count >= levels) break;
        depth.push_back({level.first, level.second.totalQuantity});
        count++;
    }
    return depth;
}

std::vector<OrderBook::DepthLevel> OrderBook::getAskDepth(size_t levels) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<DepthLevel> depth;
    size_t count = 0;
    for (const auto& level : sellLevels_) {
        if (count >= levels) break;
        depth.push_back({level.first, level.second.totalQuantity});
        count++;
    }
    return depth;
}

} // namespace MicroOptimus
