#include "include/Order.hpp"
#include "include/OrderBook.hpp"
#include "include/MatchEngine.hpp"
#include "include/Trade.hpp"
#include <iostream>
#include <iomanip>
#include <memory>

using namespace MicroOptimus;

void printDepth(const std::vector<OrderBook::DepthLevel>& depth, const std::string& side) {
    std::cout << side << " Depth:" << std::endl;
    for (const auto& level : depth) {
        std::cout << "  " << std::fixed << std::setprecision(2) 
                  << level.price << " | " << level.quantity << std::endl;
    }
}

int main() {
    std::cout << "=== MicroOptimus Trading System Demo ===" << std::endl;
    std::cout << std::endl;

    // Create orderbook for BTCUSD
    OrderBook orderbook("BTCUSD");
    MatchEngine matchEngine;

    std::cout << "Creating orderbook for BTCUSD..." << std::endl;

    // Set up callbacks
    matchEngine.setTradeCallback([](const Trade& trade) {
        std::cout << "TRADE: " << trade.symbol << " " 
                  << trade.quantity << " @ " << std::fixed << std::setprecision(2) 
                  << trade.price << " (Buy Order: " << trade.buyOrderId 
                  << ", Sell Order: " << trade.sellOrderId << ")" << std::endl;
    });

    matchEngine.setOrderUpdateCallback([](const Order& order) {
        std::cout << "ORDER UPDATE: " << order.orderId << " " 
                  << (order.side == OrderSide::BUY ? "BUY" : "SELL") << " "
                  << order.quantity << " @ " << std::fixed << std::setprecision(2) 
                  << order.price;
        
        switch (order.status) {
            case OrderStatus::NEW: std::cout << " NEW"; break;
            case OrderStatus::PARTIALLY_FILLED: std::cout << " PARTIALLY_FILLED"; break;
            case OrderStatus::FILLED: std::cout << " FILLED"; break;
            case OrderStatus::CANCELLED: std::cout << " CANCELLED"; break;
            case OrderStatus::REJECTED: std::cout << " REJECTED"; break;
        }
        
        std::cout << " (Filled: " << order.filledQuantity << ")" << std::endl;
    });

    std::cout << std::endl << "=== Scenario 1: Building Orderbook ===" << std::endl;

    // Add buy orders
    auto buyOrder1 = std::make_shared<Order>(1, "BTCUSD", OrderSide::BUY, OrderType::LIMIT, 50000.00, 10, "Client1");
    auto buyOrder2 = std::make_shared<Order>(2, "BTCUSD", OrderSide::BUY, OrderType::LIMIT, 49990.00, 15, "Client2");
    auto buyOrder3 = std::make_shared<Order>(3, "BTCUSD", OrderSide::BUY, OrderType::LIMIT, 49980.00, 20, "Client3");

    orderbook.addOrder(buyOrder1);
    orderbook.addOrder(buyOrder2);
    orderbook.addOrder(buyOrder3);

    // Add sell orders
    auto sellOrder1 = std::make_shared<Order>(4, "BTCUSD", OrderSide::SELL, OrderType::LIMIT, 50010.00, 12, "Client4");
    auto sellOrder2 = std::make_shared<Order>(5, "BTCUSD", OrderSide::SELL, OrderType::LIMIT, 50020.00, 18, "Client5");
    auto sellOrder3 = std::make_shared<Order>(6, "BTCUSD", OrderSide::SELL, OrderType::LIMIT, 50030.00, 25, "Client6");

    orderbook.addOrder(sellOrder1);
    orderbook.addOrder(sellOrder2);
    orderbook.addOrder(sellOrder3);

    std::cout << std::endl;
    std::cout << "Best Bid: " << std::fixed << std::setprecision(2) << orderbook.getBestBid() << std::endl;
    std::cout << "Best Ask: " << std::fixed << std::setprecision(2) << orderbook.getBestAsk() << std::endl;
    std::cout << "Mid Price: " << std::fixed << std::setprecision(2) << orderbook.getMidPrice() << std::endl;
    std::cout << std::endl;

    printDepth(orderbook.getBidDepth(5), "BID");
    std::cout << std::endl;
    printDepth(orderbook.getAskDepth(5), "ASK");

    std::cout << std::endl << "=== Scenario 2: Aggressive Buy Order (Market Taker) ===" << std::endl;

    // Aggressive buy order that crosses the spread
    auto aggressiveBuy = std::make_shared<Order>(7, "BTCUSD", OrderSide::BUY, OrderType::LIMIT, 50015.00, 20, "Client7");
    auto trades = matchEngine.matchOrder(aggressiveBuy, orderbook);

    std::cout << std::endl;
    std::cout << "Trades executed: " << trades.size() << std::endl;
    std::cout << "Best Bid: " << orderbook.getBestBid() << std::endl;
    std::cout << "Best Ask: " << orderbook.getBestAsk() << std::endl;

    std::cout << std::endl << "=== Scenario 3: Market Order ===" << std::endl;

    // Market sell order
    auto marketSell = std::make_shared<Order>(8, "BTCUSD", OrderSide::SELL, OrderType::MARKET, 0.0, 15, "Client8");
    trades = matchEngine.matchOrder(marketSell, orderbook);

    std::cout << std::endl;
    std::cout << "Market order trades: " << trades.size() << std::endl;
    std::cout << "Best Bid: " << orderbook.getBestBid() << std::endl;
    std::cout << "Best Ask: " << orderbook.getBestAsk() << std::endl;

    std::cout << std::endl << "=== Matching Statistics ===" << std::endl;
    auto stats = matchEngine.getStatistics();
    std::cout << "Total Trades: " << stats.totalTradesExecuted << std::endl;
    std::cout << "Total Volume: " << stats.totalVolumeTraded << std::endl;
    std::cout << "Total Value: $" << std::fixed << std::setprecision(2) << stats.totalValueTraded << std::endl;
    std::cout << "Average Trade Size: " << std::fixed << std::setprecision(2) << stats.averageTradeSize << std::endl;
    std::cout << "Average Trade Price: $" << std::fixed << std::setprecision(2) << stats.averageTradePrice << std::endl;

    std::cout << std::endl << "=== Demo Complete ===" << std::endl;

    return 0;
}
