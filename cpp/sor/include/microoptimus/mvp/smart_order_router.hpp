#pragma once

#include <array>

#include "microoptimus/mvp/sor_types.hpp"

namespace microoptimus::mvp {

class SmartOrderRouter {
public:
    SmartOrderRouter();

    void initialize();
    void configure_venue(const VenueConfig& cfg);
    void set_internal_liquidity_threshold(common::Quantity threshold);

    RoutingDecision route_order(const OrderRequest& request) const;

private:
    std::array<VenueConfig, 2> venues_{};
    common::Quantity internal_liquidity_threshold_ = 0;
    bool initialized_ = false;
};

} // namespace microoptimus::mvp

