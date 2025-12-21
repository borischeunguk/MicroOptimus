#include <gtest/gtest.h>
#include "microoptimus/sor/venue_scorer.hpp"
#include <memory>

namespace microoptimus {
namespace sor {
namespace test {

class VenueScorerTest : public ::testing::Test {
protected:
    void SetUp() override {
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

        scorer_->configureVenue(VenueType::ARCA,
            VenueConfig(VenueType::ARCA, 75, true, 300000, 300000, 900000, 250));
    }

    std::unique_ptr<VenueScorer> scorer_;
};

// Test basic venue configuration
TEST_F(VenueScorerTest, ConfigureVenue) {
    VenueConfig config(VenueType::IEX, 70, true, 200000, 350000, 880000, 300);
    scorer_->configureVenue(VenueType::IEX, config);

    const auto* retrievedConfig = scorer_->getVenueConfig(VenueType::IEX);
    ASSERT_NE(retrievedConfig, nullptr);
    EXPECT_EQ(retrievedConfig->venueType, VenueType::IEX);
    EXPECT_EQ(retrievedConfig->priority, 70);
    EXPECT_TRUE(retrievedConfig->enabled);
    EXPECT_EQ(retrievedConfig->maxOrderSize, 200000);
}

// Test best venue selection for small orders
TEST_F(VenueScorerTest, SelectBestVenueSmallOrder) {
    OrderRequest order(1, "AAPL", Side::BUY, OrderType::LIMIT, 15000000, 100, 0);

    VenueType bestVenue = scorer_->selectBestVenue(order);

    // Internal venue should be selected due to highest priority and low latency
    EXPECT_EQ(bestVenue, VenueType::INTERNAL);
}

// Test best venue selection with capacity constraints
TEST_F(VenueScorerTest, SelectBestVenueWithCapacityConstraint) {
    // Order exceeds internal venue capacity
    OrderRequest order(2, "GOOGL", Side::SELL, OrderType::LIMIT, 28000000, 15000000, 0);

    VenueType bestVenue = scorer_->selectBestVenue(order);

    // Internal venue cannot handle this size, should reject
    EXPECT_EQ(bestVenue, VenueType::NONE);
}

// Test best venue selection for medium-sized orders
TEST_F(VenueScorerTest, SelectBestVenueMediumOrder) {
    OrderRequest order(3, "MSFT", Side::BUY, OrderType::LIMIT, 30000000, 50000, 0);

    VenueType bestVenue = scorer_->selectBestVenue(order);

    // Should select internal or CME
    EXPECT_TRUE(bestVenue == VenueType::INTERNAL || bestVenue == VenueType::CME);
}

// Test top venues selection
TEST_F(VenueScorerTest, SelectTopVenues) {
    OrderRequest order(4, "TSLA", Side::BUY, OrderType::LIMIT, 20000000, 1000, 0);

    auto topVenues = scorer_->selectTopVenues(order, 3);

    EXPECT_GE(topVenues.size(), 1);
    EXPECT_LE(topVenues.size(), 3);

    // First venue should be the best (highest score)
    EXPECT_EQ(topVenues[0], VenueType::INTERNAL);
}

// Test venue selection with disabled venue
TEST_F(VenueScorerTest, DisabledVenueNotSelected) {
    // Disable NASDAQ
    scorer_->configureVenue(VenueType::NASDAQ,
        VenueConfig(VenueType::NASDAQ, 85, false, 500000, 200000, 930000, 200));

    OrderRequest order(5, "AMZN", Side::BUY, OrderType::LIMIT, 18000000, 500, 0);

    auto topVenues = scorer_->selectTopVenues(order, 5);

    // NASDAQ should not be in the list
    for (const auto& venue : topVenues) {
        EXPECT_NE(venue, VenueType::NASDAQ);
    }
}

// Test empty configuration
TEST_F(VenueScorerTest, EmptyScorer) {
    VenueScorer emptyScorer;
    OrderRequest order(6, "FB", Side::SELL, OrderType::LIMIT, 25000000, 100, 0);

    VenueType bestVenue = emptyScorer.selectBestVenue(order);
    EXPECT_EQ(bestVenue, VenueType::NONE);

    auto topVenues = emptyScorer.selectTopVenues(order, 3);
    EXPECT_TRUE(topVenues.empty());
}

// Test configuration retrieval for non-existent venue
TEST_F(VenueScorerTest, GetNonExistentVenueConfig) {
    const auto* config = scorer_->getVenueConfig(VenueType::BATS);
    EXPECT_EQ(config, nullptr);
}

// Test venue scoring with zero quantity order
TEST_F(VenueScorerTest, ZeroQuantityOrder) {
    OrderRequest order(7, "NFLX", Side::BUY, OrderType::LIMIT, 40000000, 0, 0);

    // Zero quantity orders should not match any venue
    VenueType bestVenue = scorer_->selectBestVenue(order);
    EXPECT_EQ(bestVenue, VenueType::NONE);
}

// Test multiple configuration updates
TEST_F(VenueScorerTest, UpdateVenueConfiguration) {
    // Initial configuration
    const auto* config1 = scorer_->getVenueConfig(VenueType::CME);
    ASSERT_NE(config1, nullptr);
    EXPECT_EQ(config1->priority, 90);

    // Update configuration
    scorer_->configureVenue(VenueType::CME,
        VenueConfig(VenueType::CME, 95, true, 2000000, 100000, 980000, 50));

    // Verify update
    const auto* config2 = scorer_->getVenueConfig(VenueType::CME);
    ASSERT_NE(config2, nullptr);
    EXPECT_EQ(config2->priority, 95);
    EXPECT_EQ(config2->maxOrderSize, 2000000);
}

} // namespace test
} // namespace sor
} // namespace microoptimus

