#include <gtest/gtest.h>

#include "microoptimus/common/types.hpp"
#include "microoptimus/mvp/smart_order_router.hpp"

namespace {

using namespace microoptimus;

TEST(SmartOrderRouterMvpTest, PrefersCmeWhenLatencyAndFillAreBetter) {
    mvp::SmartOrderRouter router;
    router.initialize();
    router.set_internal_liquidity_threshold(0);

    router.configure_venue(mvp::VenueConfig{common::VenueId::Cme, 90, true, 1'000'000, 120'000, 0.97, 0.00010});
    router.configure_venue(mvp::VenueConfig{common::VenueId::Nasq, 85, true, 1'000'000, 180'000, 0.90, 0.00015});

    mvp::OrderRequest req;
    req.quantity = 1'000;
    req.price = 1'500;

    const auto decision = router.route_order(req);
    EXPECT_EQ(decision.action, mvp::RoutingAction::RouteExternal);
    EXPECT_EQ(decision.venue, common::VenueId::Cme);
}

TEST(SmartOrderRouterMvpTest, RoutesInternalWhenThresholdAllows) {
    mvp::SmartOrderRouter router;
    router.initialize();
    router.set_internal_liquidity_threshold(2'000);

    mvp::OrderRequest req;
    req.quantity = 500;

    const auto decision = router.route_order(req);
    EXPECT_EQ(decision.action, mvp::RoutingAction::RouteInternal);
    EXPECT_EQ(decision.venue, common::VenueId::Internal);
}

} // namespace

