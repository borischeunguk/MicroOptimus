#include "microoptimus/sor/smart_order_router.hpp"
#include <algorithm>

namespace microoptimus {
namespace sor {

SmartOrderRouter::SmartOrderRouter()
    : venueScorer_(),
      riskManager_(),
      orderSplitter_() {
}

int SmartOrderRouter::initialize(const std::string& /* configPath */,
                                 const std::string& /* sharedMemoryPath */) {
    if (initialized_) {
        return 0;
    }

    // Initialize default venue configurations
    initializeDefaultVenues();

    initialized_ = true;
    return 0;
}

RoutingDecision SmartOrderRouter::routeOrder(const OrderRequest& order) {
    if (!initialized_) {
        return RoutingDecision::rejected("SOR not initialized");
    }

    auto start = std::chrono::high_resolution_clock::now();
    totalOrders_++;

    // Risk checks
    if (!riskManager_.passesRiskChecks(order)) {
        rejectedOrders_++;
        return RoutingDecision::rejected("Risk check failed");
    }

    // Route decision based on order size
    RoutingDecision decision;
    if (order.quantity <= 10000) {
        // Small order - route to single best venue
        decision = routeSingleVenue(order);
    } else {
        // Large order - consider splitting across venues
        decision = routeWithSplitting(order);
    }

    // Update statistics
    updateLatencyStats(start);
    updateRoutingStats(decision);

    return decision;
}

bool SmartOrderRouter::configureVenue(VenueType venue, int priority, bool enabled,
                                     int64_t maxOrderSize, int64_t avgLatencyNanos,
                                     int64_t fillRate, int64_t feesPerShare) {
    if (!initialized_) {
        return false;
    }

    VenueConfig config(venue, priority, enabled, maxOrderSize,
                      avgLatencyNanos, fillRate, feesPerShare);
    venueScorer_.configureVenue(venue, config);
    return true;
}

void SmartOrderRouter::getStatistics(int64_t* stats) const {
    stats[0] = totalOrders_.load();
    stats[1] = internalRoutes_.load();
    stats[2] = externalRoutes_.load();
    stats[3] = rejectedOrders_.load();

    uint64_t total = totalOrders_.load();
    stats[4] = total > 0 ? totalLatencyNanos_.load() / total : 0;
    stats[5] = maxLatencyNanos_.load();
    stats[6] = minLatencyNanos_.load() == UINT64_MAX ? 0 : minLatencyNanos_.load();
}

void SmartOrderRouter::shutdown() {
    initialized_ = false;
}

void SmartOrderRouter::initializeDefaultVenues() {
    // Internal venue: highest priority, zero fees, fastest
    venueScorer_.configureVenue(VenueType::INTERNAL,
        VenueConfig(VenueType::INTERNAL, 100, true, 10000000, 5000, 1000000, 0));

    // CME: high priority, good fill rates
    venueScorer_.configureVenue(VenueType::CME,
        VenueConfig(VenueType::CME, 90, true, 1000000, 150000, 950000, 100));

    // NASDAQ: medium priority
    venueScorer_.configureVenue(VenueType::NASDAQ,
        VenueConfig(VenueType::NASDAQ, 85, true, 500000, 200000, 930000, 200));

    // NYSE: medium priority
    venueScorer_.configureVenue(VenueType::NYSE,
        VenueConfig(VenueType::NYSE, 80, true, 500000, 250000, 910000, 200));
}

RoutingDecision SmartOrderRouter::routeSingleVenue(const OrderRequest& order) {
    VenueType bestVenue = venueScorer_.selectBestVenue(order);

    if (bestVenue == VenueType::NONE) {
        return RoutingDecision::rejected("No suitable venue");
    }

    const auto* config = venueScorer_.getVenueConfig(bestVenue);
    int64_t estimatedFillTime = config ? config->avgLatencyNanos : 50000;

    return RoutingDecision::singleVenue(bestVenue, order.quantity, estimatedFillTime);
}

RoutingDecision SmartOrderRouter::routeWithSplitting(const OrderRequest& order) {
    auto topVenues = venueScorer_.selectTopVenues(order, 3);

    if (topVenues.empty()) {
        return RoutingDecision::rejected("No venues available");
    }

    if (topVenues.size() == 1) {
        return routeSingleVenue(order);
    }

    auto allocations = orderSplitter_.splitOrder(order, topVenues, venueScorer_);

    if (allocations.empty()) {
        return RoutingDecision::rejected("Order splitting failed");
    }

    // Calculate estimated fill time (max of all venues)
    int64_t maxFillTime = 0;
    for (const auto& alloc : allocations) {
        const auto* config = venueScorer_.getVenueConfig(alloc.venue);
        if (config) {
            maxFillTime = std::max(maxFillTime, config->avgLatencyNanos);
        }
    }

    RoutingDecision decision;
    decision.action = RoutingAction::SPLIT_ORDER;
    decision.allocations = allocations;
    decision.primaryVenue = allocations.empty() ? VenueType::NONE : allocations[0].venue;
    decision.quantity = order.quantity;
    decision.estimatedFillTimeNanos = maxFillTime;

    return decision;
}

void SmartOrderRouter::updateLatencyStats(const std::chrono::high_resolution_clock::time_point& start) {
    auto end = std::chrono::high_resolution_clock::now();
    auto latency = std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count();

    totalLatencyNanos_ += latency;

    uint64_t latencyUint = static_cast<uint64_t>(latency);
    uint64_t currentMax = maxLatencyNanos_.load();
    while (latencyUint > currentMax &&
           !maxLatencyNanos_.compare_exchange_weak(currentMax, latencyUint));

    uint64_t currentMin = minLatencyNanos_.load();
    while (latencyUint < currentMin &&
           !minLatencyNanos_.compare_exchange_weak(currentMin, latencyUint));
}

void SmartOrderRouter::updateRoutingStats(const RoutingDecision& decision) {
    switch (decision.action) {
        case RoutingAction::ROUTE_INTERNAL:
            internalRoutes_++;
            break;
        case RoutingAction::ROUTE_EXTERNAL:
        case RoutingAction::SPLIT_ORDER:
            externalRoutes_++;
            break;
        case RoutingAction::REJECT:
            rejectedOrders_++;
            break;
    }
}

} // namespace sor
} // namespace microoptimus

