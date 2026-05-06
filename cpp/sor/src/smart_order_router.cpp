#include "microoptimus/mvp/smart_order_router.hpp"

#include <limits>

namespace microoptimus::mvp {

SmartOrderRouter::SmartOrderRouter() {
    venues_[0] = VenueConfig{common::VenueId::Cme, 90, true, 1'500'000, 130'000, 0.95, 0.00010};
    venues_[1] = VenueConfig{common::VenueId::Nasq, 85, true, 1'200'000, 170'000, 0.92, 0.00015};
}

void SmartOrderRouter::initialize() {
    initialized_ = true;
}

void SmartOrderRouter::configure_venue(const VenueConfig& cfg) {
    if (cfg.venue == common::VenueId::Cme) {
        venues_[0] = cfg;
    } else if (cfg.venue == common::VenueId::Nasq) {
        venues_[1] = cfg;
    }
}

void SmartOrderRouter::set_internal_liquidity_threshold(common::Quantity threshold) {
    internal_liquidity_threshold_ = threshold;
}

RoutingDecision SmartOrderRouter::route_order(const OrderRequest& request) const {
    if (!initialized_ || request.quantity == 0) {
        return RoutingDecision{RoutingAction::Reject, common::VenueId::Internal, 0};
    }

    if (request.quantity <= internal_liquidity_threshold_) {
        return RoutingDecision{RoutingAction::RouteInternal, common::VenueId::Internal, request.quantity};
    }

    double best_score = -std::numeric_limits<double>::infinity();
    common::VenueId best_venue = common::VenueId::Cme;

    for (const auto& venue : venues_) {
        if (!venue.enabled || request.quantity > venue.max_order_size) {
            continue;
        }

        const double latency_score = 1.0 / static_cast<double>(venue.avg_latency_ns + 1);
        const double fill_score = venue.fill_rate;
        const double fee_score = 1.0 - venue.fee_rate;
        const double priority_score = static_cast<double>(venue.priority) / 100.0;

        const double score = 0.30 * priority_score + 0.30 * fill_score + 0.30 * latency_score * 1e6 + 0.10 * fee_score;
        if (score > best_score) {
            best_score = score;
            best_venue = venue.venue;
        }
    }

    if (best_score < 0.0) {
        return RoutingDecision{RoutingAction::Reject, common::VenueId::Internal, 0};
    }

    return RoutingDecision{RoutingAction::RouteExternal, best_venue, request.quantity};
}

} // namespace microoptimus::mvp

