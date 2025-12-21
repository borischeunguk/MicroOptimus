#include <gtest/gtest.h>
#include "microoptimus/sor/risk_manager.hpp"
#include <memory>

namespace microoptimus {
namespace sor {
namespace test {

class RiskManagerTest : public ::testing::Test {
protected:
    void SetUp() override {
        riskManager_ = std::make_unique<RiskManager>();
    }

    std::unique_ptr<RiskManager> riskManager_;
};

// Test valid order passes risk checks
TEST_F(RiskManagerTest, ValidOrderPasses) {
    OrderRequest order(1, "AAPL", Side::BUY, OrderType::LIMIT, 15000000, 100, 0);

    EXPECT_TRUE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersChecked(), 1);
    EXPECT_EQ(riskManager_->getOrdersRejected(), 0);
}

// Test order with zero quantity fails
TEST_F(RiskManagerTest, ZeroQuantityRejected) {
    OrderRequest order(2, "GOOGL", Side::SELL, OrderType::LIMIT, 28000000, 0, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersChecked(), 1);
    EXPECT_EQ(riskManager_->getOrdersRejected(), 1);
}

// Test order with negative quantity fails
TEST_F(RiskManagerTest, NegativeQuantityRejected) {
    OrderRequest order(3, "MSFT", Side::BUY, OrderType::LIMIT, 30000000, -100, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 1);
}

// Test order exceeding max size fails
TEST_F(RiskManagerTest, ExceedsMaxSizeRejected) {
    riskManager_->setMaxOrderSize(1000);
    OrderRequest order(4, "TSLA", Side::BUY, OrderType::LIMIT, 20000000, 5000, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 1);
}

// Test order at max size limit passes
TEST_F(RiskManagerTest, AtMaxSizePasses) {
    riskManager_->setMaxOrderSize(1000);
    OrderRequest order(5, "AMZN", Side::SELL, OrderType::LIMIT, 18000000, 1000, 0);

    EXPECT_TRUE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 0);
}

// Test limit order with zero price fails
TEST_F(RiskManagerTest, LimitOrderZeroPriceRejected) {
    OrderRequest order(6, "FB", Side::BUY, OrderType::LIMIT, 0, 100, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 1);
}

// Test limit order with negative price fails
TEST_F(RiskManagerTest, LimitOrderNegativePriceRejected) {
    OrderRequest order(7, "NFLX", Side::SELL, OrderType::LIMIT, -1000000, 50, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 1);
}

// Test market order passes (no price check)
TEST_F(RiskManagerTest, MarketOrderPasses) {
    OrderRequest order(8, "NVDA", Side::BUY, OrderType::MARKET, 0, 200, 0);

    EXPECT_TRUE(riskManager_->passesRiskChecks(order));
    EXPECT_EQ(riskManager_->getOrdersRejected(), 0);
}

// Test multiple orders statistics
TEST_F(RiskManagerTest, MultipleOrdersStatistics) {
    OrderRequest validOrder1(9, "AAPL", Side::BUY, OrderType::LIMIT, 15000000, 100, 0);
    OrderRequest validOrder2(10, "GOOGL", Side::SELL, OrderType::LIMIT, 28000000, 200, 0);
    OrderRequest invalidOrder1(11, "MSFT", Side::BUY, OrderType::LIMIT, 0, 50, 0);
    OrderRequest invalidOrder2(12, "TSLA", Side::SELL, OrderType::LIMIT, 20000000, -10, 0);

    EXPECT_TRUE(riskManager_->passesRiskChecks(validOrder1));
    EXPECT_TRUE(riskManager_->passesRiskChecks(validOrder2));
    EXPECT_FALSE(riskManager_->passesRiskChecks(invalidOrder1));
    EXPECT_FALSE(riskManager_->passesRiskChecks(invalidOrder2));

    EXPECT_EQ(riskManager_->getOrdersChecked(), 4);
    EXPECT_EQ(riskManager_->getOrdersRejected(), 2);
}

// Test default max order size
TEST_F(RiskManagerTest, DefaultMaxOrderSize) {
    // Default max order size is 1,000,000
    OrderRequest largeOrder(13, "AMZN", Side::BUY, OrderType::LIMIT, 35000000, 1000001, 0);
    OrderRequest okOrder(14, "AMZN", Side::BUY, OrderType::LIMIT, 35000000, 1000000, 0);

    EXPECT_FALSE(riskManager_->passesRiskChecks(largeOrder));
    EXPECT_TRUE(riskManager_->passesRiskChecks(okOrder));
}

// Test changing max order size
TEST_F(RiskManagerTest, ChangeMaxOrderSize) {
    OrderRequest order(15, "FB", Side::BUY, OrderType::LIMIT, 25000000, 500, 0);

    riskManager_->setMaxOrderSize(1000);
    EXPECT_TRUE(riskManager_->passesRiskChecks(order));

    riskManager_->setMaxOrderSize(400);
    EXPECT_FALSE(riskManager_->passesRiskChecks(order));

    riskManager_->setMaxOrderSize(500);
    EXPECT_TRUE(riskManager_->passesRiskChecks(order));
}

// Test stop order validation
TEST_F(RiskManagerTest, StopOrderValidation) {
    OrderRequest stopOrder(16, "NFLX", Side::SELL, OrderType::STOP, 40000000, 100, 0);

    // Stop orders should pass (price checks apply only to LIMIT orders)
    EXPECT_TRUE(riskManager_->passesRiskChecks(stopOrder));
}

// Test stop limit order with price
TEST_F(RiskManagerTest, StopLimitOrderValidation) {
    OrderRequest validStopLimit(17, "AMD", Side::BUY, OrderType::STOP_LIMIT, 8000000, 150, 0);
    OrderRequest invalidStopLimit(18, "AMD", Side::BUY, OrderType::STOP_LIMIT, 0, 150, 0);

    EXPECT_TRUE(riskManager_->passesRiskChecks(validStopLimit));
    EXPECT_FALSE(riskManager_->passesRiskChecks(invalidStopLimit));
}

} // namespace test
} // namespace sor
} // namespace microoptimus

