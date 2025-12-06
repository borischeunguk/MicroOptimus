#ifndef MATCHENGINE_HPP
#define MATCHENGINE_HPP

#include "Order.hpp"
#include "Trade.hpp"
#include "OrderBook.hpp"
#include <memory>
#include <vector>
#include <functional>

namespace MicroOptimus {

// Callback for trade notifications
using TradeCallback = std::function<void(const Trade&)>;
using OrderUpdateCallback = std::function<void(const Order&)>;

class MatchEngine {
private:
    uint64_t nextTradeId_;
    std::vector<Trade> trades_;
    TradeCallback tradeCallback_;
    OrderUpdateCallback orderUpdateCallback_;

    // Statistics
    uint64_t totalTradesExecuted_;
    uint64_t totalVolumeTraded_;
    double totalValueTraded_;

public:
    MatchEngine()
        : nextTradeId_(1), totalTradesExecuted_(0),
          totalVolumeTraded_(0), totalValueTraded_(0.0) {}

    // Set callback for trade notifications
    void setTradeCallback(TradeCallback callback) {
        tradeCallback_ = callback;
    }

    // Set callback for order update notifications
    void setOrderUpdateCallback(OrderUpdateCallback callback) {
        orderUpdateCallback_ = callback;
    }

    // Match a new incoming order against the orderbook
    // Returns list of trades generated
    std::vector<Trade> matchOrder(std::shared_ptr<Order> order, OrderBook& orderbook);

    // Execute a market order
    std::vector<Trade> executeMarketOrder(std::shared_ptr<Order> order, OrderBook& orderbook);

    // Execute a limit order
    std::vector<Trade> executeLimitOrder(std::shared_ptr<Order> order, OrderBook& orderbook);

    // Get all executed trades
    const std::vector<Trade>& getTrades() const { return trades_; }

    // Get matching statistics
    struct Statistics {
        uint64_t totalTradesExecuted;
        uint64_t totalVolumeTraded;
        double totalValueTraded;
        double averageTradeSize;
        double averageTradePrice;
    };
    Statistics getStatistics() const;

private:
    // Create a trade between two orders
    Trade createTrade(std::shared_ptr<Order> aggressorOrder,
                      std::shared_ptr<Order> passiveOrder,
                      uint64_t quantity);

    // Update order after partial or full fill
    void updateOrder(std::shared_ptr<Order> order, uint64_t filledQty);

    // Notify trade execution
    void notifyTrade(const Trade& trade);

    // Notify order update
    void notifyOrderUpdate(const Order& order);
};

} // namespace MicroOptimus

#endif // MATCHENGINE_HPP
