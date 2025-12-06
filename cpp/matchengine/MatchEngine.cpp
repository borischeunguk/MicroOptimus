#include "../include/MatchEngine.hpp"
#include <algorithm>
#include <stdexcept>

namespace MicroOptimus {

std::vector<Trade> MatchEngine::matchOrder(std::shared_ptr<Order> order, OrderBook& orderbook) {
    if (order->type == OrderType::MARKET) {
        return executeMarketOrder(order, orderbook);
    } else {
        return executeLimitOrder(order, orderbook);
    }
}

std::vector<Trade> MatchEngine::executeMarketOrder(std::shared_ptr<Order> order, OrderBook& orderbook) {
    std::vector<Trade> trades;

    if (order->side == OrderSide::BUY) {
        // Match against sell orders (asks)
        while (order->remainingQuantity() > 0) {
            double bestAsk = orderbook.getBestAsk();
            if (bestAsk == 0.0) {
                // No more liquidity
                break;
            }

            auto sellOrders = orderbook.getSellOrdersAtPrice(bestAsk);
            if (sellOrders.empty()) {
                break;
            }

            for (auto& sellOrder : sellOrders) {
                if (order->remainingQuantity() == 0) {
                    break;
                }

                uint64_t matchQty = std::min(order->remainingQuantity(), sellOrder->remainingQuantity());
                Trade trade = createTrade(order, sellOrder, matchQty);
                trades.push_back(trade);
                trades_.push_back(trade);

                updateOrder(order, matchQty);
                updateOrder(sellOrder, matchQty);

                notifyTrade(trade);

                if (sellOrder->remainingQuantity() == 0) {
                    orderbook.cancelOrder(sellOrder->orderId);
                }
            }
        }
    } else {
        // Match against buy orders (bids)
        while (order->remainingQuantity() > 0) {
            double bestBid = orderbook.getBestBid();
            if (bestBid == 0.0) {
                // No more liquidity
                break;
            }

            auto buyOrders = orderbook.getBuyOrdersAtPrice(bestBid);
            if (buyOrders.empty()) {
                break;
            }

            for (auto& buyOrder : buyOrders) {
                if (order->remainingQuantity() == 0) {
                    break;
                }

                uint64_t matchQty = std::min(order->remainingQuantity(), buyOrder->remainingQuantity());
                Trade trade = createTrade(buyOrder, order, matchQty);
                trades.push_back(trade);
                trades_.push_back(trade);

                updateOrder(order, matchQty);
                updateOrder(buyOrder, matchQty);

                notifyTrade(trade);

                if (buyOrder->remainingQuantity() == 0) {
                    orderbook.cancelOrder(buyOrder->orderId);
                }
            }
        }
    }

    // Market order should be cancelled if not fully filled
    if (order->remainingQuantity() > 0) {
        order->status = OrderStatus::CANCELLED;
        notifyOrderUpdate(*order);
    }

    return trades;
}

std::vector<Trade> MatchEngine::executeLimitOrder(std::shared_ptr<Order> order, OrderBook& orderbook) {
    std::vector<Trade> trades;
    bool canMatch = true;

    if (order->side == OrderSide::BUY) {
        // Match against sell orders if buy price >= ask price
        while (order->remainingQuantity() > 0 && canMatch) {
            double bestAsk = orderbook.getBestAsk();
            if (bestAsk == 0.0 || order->price < bestAsk) {
                canMatch = false;
                break;
            }

            auto sellOrders = orderbook.getSellOrdersAtPrice(bestAsk);
            if (sellOrders.empty()) {
                canMatch = false;
                break;
            }

            for (auto& sellOrder : sellOrders) {
                if (order->remainingQuantity() == 0) {
                    break;
                }

                uint64_t matchQty = std::min(order->remainingQuantity(), sellOrder->remainingQuantity());
                Trade trade = createTrade(order, sellOrder, matchQty);
                trades.push_back(trade);
                trades_.push_back(trade);

                updateOrder(order, matchQty);
                updateOrder(sellOrder, matchQty);

                notifyTrade(trade);

                if (sellOrder->remainingQuantity() == 0) {
                    orderbook.cancelOrder(sellOrder->orderId);
                }
            }
        }
    } else {
        // Match against buy orders if sell price <= bid price
        while (order->remainingQuantity() > 0 && canMatch) {
            double bestBid = orderbook.getBestBid();
            if (bestBid == 0.0 || order->price > bestBid) {
                canMatch = false;
                break;
            }

            auto buyOrders = orderbook.getBuyOrdersAtPrice(bestBid);
            if (buyOrders.empty()) {
                canMatch = false;
                break;
            }

            for (auto& buyOrder : buyOrders) {
                if (order->remainingQuantity() == 0) {
                    break;
                }

                uint64_t matchQty = std::min(order->remainingQuantity(), buyOrder->remainingQuantity());
                Trade trade = createTrade(buyOrder, order, matchQty);
                trades.push_back(trade);
                trades_.push_back(trade);

                updateOrder(order, matchQty);
                updateOrder(buyOrder, matchQty);

                notifyTrade(trade);

                if (buyOrder->remainingQuantity() == 0) {
                    orderbook.cancelOrder(buyOrder->orderId);
                }
            }
        }
    }

    // Handle special order types
    if (order->type == OrderType::IOC) {
        // Immediate or Cancel - cancel remaining quantity
        if (order->remainingQuantity() > 0) {
            order->status = OrderStatus::CANCELLED;
            notifyOrderUpdate(*order);
            return trades;
        }
    } else if (order->type == OrderType::FOK) {
        // Fill or Kill - if not completely filled, reject the order
        if (order->remainingQuantity() > 0) {
            order->status = OrderStatus::REJECTED;
            notifyOrderUpdate(*order);
            // Rollback trades (in production, this would need transaction support)
            return std::vector<Trade>();
        }
    }

    // Add remaining quantity to orderbook if it's a regular limit order
    if (order->remainingQuantity() > 0 && order->type == OrderType::LIMIT) {
        orderbook.addOrder(order);
    }

    return trades;
}

Trade MatchEngine::createTrade(std::shared_ptr<Order> aggressorOrder,
                                std::shared_ptr<Order> passiveOrder,
                                uint64_t quantity) {
    // Trade executes at the passive order's price (price-time priority)
    double tradePrice = passiveOrder->price;

    Trade trade(nextTradeId_++,
                aggressorOrder->side == OrderSide::BUY ? aggressorOrder->orderId : passiveOrder->orderId,
                aggressorOrder->side == OrderSide::SELL ? aggressorOrder->orderId : passiveOrder->orderId,
                aggressorOrder->symbol,
                tradePrice,
                quantity,
                aggressorOrder->side == OrderSide::BUY ? aggressorOrder->clientId : passiveOrder->clientId,
                aggressorOrder->side == OrderSide::SELL ? aggressorOrder->clientId : passiveOrder->clientId);

    // Update statistics
    totalTradesExecuted_++;
    totalVolumeTraded_ += quantity;
    totalValueTraded_ += tradePrice * quantity;

    return trade;
}

void MatchEngine::updateOrder(std::shared_ptr<Order> order, uint64_t filledQty) {
    order->filledQuantity += filledQty;
    
    if (order->remainingQuantity() == 0) {
        order->status = OrderStatus::FILLED;
    } else if (order->filledQuantity > 0) {
        order->status = OrderStatus::PARTIALLY_FILLED;
    }

    notifyOrderUpdate(*order);
}

void MatchEngine::notifyTrade(const Trade& trade) {
    if (tradeCallback_) {
        tradeCallback_(trade);
    }
}

void MatchEngine::notifyOrderUpdate(const Order& order) {
    if (orderUpdateCallback_) {
        orderUpdateCallback_(order);
    }
}

MatchEngine::Statistics MatchEngine::getStatistics() const {
    Statistics stats;
    stats.totalTradesExecuted = totalTradesExecuted_;
    stats.totalVolumeTraded = totalVolumeTraded_;
    stats.totalValueTraded = totalValueTraded_;
    stats.averageTradeSize = totalTradesExecuted_ > 0 ?
        static_cast<double>(totalVolumeTraded_) / totalTradesExecuted_ : 0.0;
    stats.averageTradePrice = totalVolumeTraded_ > 0 ?
        totalValueTraded_ / totalVolumeTraded_ : 0.0;
    return stats;
}

} // namespace MicroOptimus
