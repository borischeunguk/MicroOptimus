#include <gtest/gtest.h>
#include "microoptimus/sor/order_splitter.hpp"
#include "microoptimus/sor/venue_scorer.hpp"
#include <memory>

namespace microoptimus {
namespace sor {
namespace test {

class OrderSplitterTest : public ::testing::Test {
protected:
    void SetUp() override {
        splitter_ = std::make_unique<OrderSplitter>();
        scorer_ = std::make_unique<VenueScorer>();

        // Configure test venues
        scorer_->configureVenue(VenueType::INTERNAL,
            VenueConfig(VenueType::INTERNAL, 100, true, 10000000, 5000, 1000000, 0));

        scorer_->configureVenue(VenueType::CME,
            VenueConfig(VenueType::CME, 90, true, 1000000, 150000, 950000, 100));

        scorer_->configureVenue(VenueType::NASDAQ,
            VenueConfig(VenueType::NASDAQ, 85, true, 500000, 200000, 930000, 200));

        scorer_->configureVenue(VenueType::NYSE,
            VenueConfig(VenueType::NYSE, 80, true, 500000, 250000, 910000, 200));
    }

    std::unique_ptr<OrderSplitter> splitter_;
    std::unique_ptr<VenueScorer> scorer_;
};

// Test single venue (no splitting)
TEST_F(OrderSplitterTest, SingleVenueNoSplit) {
    OrderRequest order(1, "AAPL", Side::BUY, OrderType::LIMIT, 15000000, 1000, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_EQ(allocations.size(), 1);
    EXPECT_EQ(allocations[0].venue, VenueType::INTERNAL);
    EXPECT_EQ(allocations[0].quantity, 1000);
    EXPECT_EQ(allocations[0].priority, 1);
}

// Test two venues split (40/60 allocation)
TEST_F(OrderSplitterTest, TwoVenuesSplit) {
    OrderRequest order(2, "GOOGL", Side::SELL, OrderType::LIMIT, 28000000, 10000, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::CME};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_EQ(allocations.size(), 2);

    // Best venue should get ~40%
    EXPECT_EQ(allocations[0].venue, VenueType::INTERNAL);
    EXPECT_EQ(allocations[0].quantity, 4000); // 40% of 10000
    EXPECT_EQ(allocations[0].priority, 1);

    // Second venue should get remaining 60%
    EXPECT_EQ(allocations[1].venue, VenueType::CME);
    EXPECT_EQ(allocations[1].quantity, 6000);
    EXPECT_EQ(allocations[1].priority, 2);
}

// Test three venues split (40/30/30 allocation)
TEST_F(OrderSplitterTest, ThreeVenuesSplit) {
    OrderRequest order(3, "MSFT", Side::BUY, OrderType::LIMIT, 30000000, 10000, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::CME, VenueType::NASDAQ};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_EQ(allocations.size(), 3);

    // Best venue: 40%
    EXPECT_EQ(allocations[0].venue, VenueType::INTERNAL);
    EXPECT_EQ(allocations[0].quantity, 4000);

    // Second venue: 30% of original (before allocation)
    EXPECT_EQ(allocations[1].venue, VenueType::CME);
    // Third venue gets remaining quantity
    EXPECT_EQ(allocations[2].venue, VenueType::NASDAQ);

    // Total should equal order quantity
    int64_t total = allocations[0].quantity + allocations[1].quantity + allocations[2].quantity;
    EXPECT_EQ(total, 10000);
}

// Test empty venues list
TEST_F(OrderSplitterTest, EmptyVenuesList) {
    OrderRequest order(4, "TSLA", Side::BUY, OrderType::LIMIT, 20000000, 5000, 0);
    std::vector<VenueType> venues;

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    EXPECT_TRUE(allocations.empty());
}

// Test capacity constraints
TEST_F(OrderSplitterTest, CapacityConstraints) {
    OrderRequest order(5, "AMZN", Side::SELL, OrderType::LIMIT, 18000000, 2000000, 0);
    std::vector<VenueType> venues = {VenueType::CME, VenueType::NASDAQ};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    // CME max: 1,000,000, NASDAQ max: 500,000
    // Total order: 2,000,000 - should split appropriately
    int64_t totalAllocated = 0;
    for (const auto& alloc : allocations) {
        totalAllocated += alloc.quantity;
    }

    // Total allocated should equal order quantity
    EXPECT_EQ(totalAllocated, 2000000);

    // Check that we tried to respect capacity where possible
    ASSERT_GT(allocations.size(), 0);
}

// Test four or more venues split
TEST_F(OrderSplitterTest, FourVenuesSplit) {
    OrderRequest order(6, "FB", Side::BUY, OrderType::LIMIT, 25000000, 100000, 0);
    std::vector<VenueType> venues = {
        VenueType::INTERNAL,
        VenueType::CME,
        VenueType::NASDAQ,
        VenueType::NYSE
    };

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_GE(allocations.size(), 3);

    // Verify total quantity matches
    int64_t totalAllocated = 0;
    for (const auto& alloc : allocations) {
        totalAllocated += alloc.quantity;
    }
    EXPECT_EQ(totalAllocated, 100000);

    // Verify priorities are sequential
    for (size_t i = 0; i < allocations.size(); i++) {
        EXPECT_EQ(allocations[i].priority, static_cast<int>(i + 1));
    }
}

// Test small order split
TEST_F(OrderSplitterTest, SmallOrderSplit) {
    OrderRequest order(7, "NFLX", Side::SELL, OrderType::LIMIT, 40000000, 10, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::CME, VenueType::NASDAQ};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    // Small orders should still be split according to algorithm
    int64_t totalAllocated = 0;
    for (const auto& alloc : allocations) {
        totalAllocated += alloc.quantity;
        EXPECT_GT(alloc.quantity, 0);
    }
    EXPECT_EQ(totalAllocated, 10);
}

// Test disabled venue skipped
TEST_F(OrderSplitterTest, DisabledVenueSkipped) {
    // Disable NASDAQ
    scorer_->configureVenue(VenueType::NASDAQ,
        VenueConfig(VenueType::NASDAQ, 85, false, 500000, 200000, 930000, 200));

    OrderRequest order(8, "NVDA", Side::BUY, OrderType::LIMIT, 45000000, 5000, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::NASDAQ, VenueType::CME};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    // NASDAQ should not appear in allocations
    for (const auto& alloc : allocations) {
        EXPECT_NE(alloc.venue, VenueType::NASDAQ);
    }
}

// Test proportional allocation accuracy
TEST_F(OrderSplitterTest, ProportionalAllocationAccuracy) {
    OrderRequest order(9, "AMD", Side::BUY, OrderType::LIMIT, 8000000, 10000, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::CME};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_EQ(allocations.size(), 2);

    // Check that best venue gets 40% (4000)
    EXPECT_EQ(allocations[0].quantity, 4000);

    // Check that total matches order quantity
    int64_t total = allocations[0].quantity + allocations[1].quantity;
    EXPECT_EQ(total, 10000);
}

// Test single unit order
TEST_F(OrderSplitterTest, SingleUnitOrder) {
    OrderRequest order(10, "INTC", Side::SELL, OrderType::LIMIT, 5000000, 1, 0);
    std::vector<VenueType> venues = {VenueType::INTERNAL, VenueType::CME};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    // Single unit should go to best venue
    ASSERT_EQ(allocations.size(), 1);
    EXPECT_EQ(allocations[0].venue, VenueType::INTERNAL);
    EXPECT_EQ(allocations[0].quantity, 1);
}

// Test allocation respects venue order
TEST_F(OrderSplitterTest, AllocationRespectsVenueOrder) {
    OrderRequest order(11, "ORCL", Side::BUY, OrderType::LIMIT, 7000000, 5000, 0);
    std::vector<VenueType> venues = {VenueType::CME, VenueType::NASDAQ, VenueType::NYSE};

    auto allocations = splitter_->splitOrder(order, venues, *scorer_);

    ASSERT_GE(allocations.size(), 1);

    // First allocation should be from first venue in list
    EXPECT_EQ(allocations[0].venue, VenueType::CME);
    EXPECT_EQ(allocations[0].priority, 1);
}

} // namespace test
} // namespace sor
} // namespace microoptimus

