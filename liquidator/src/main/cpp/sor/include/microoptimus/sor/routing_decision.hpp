#pragma once

#include "types.hpp"
#include "venue_config.hpp"
#include <string>
#include <vector>

namespace microoptimus {
namespace sor {

/**
 * Routing decision result
 */
struct RoutingDecision {
    RoutingAction action;
    VenueType primaryVenue;
    std::vector<VenueAllocation> allocations;
    int64_t quantity;
    int64_t estimatedFillTimeNanos;
    std::string rejectReason;

    RoutingDecision()
        : action(RoutingAction::REJECT),
          primaryVenue(VenueType::NONE),
          quantity(0),
          estimatedFillTimeNanos(0) {}

    /**
     * Create a single-venue routing decision
     */
    static RoutingDecision singleVenue(VenueType venue, int64_t qty, int64_t fillTime) {
        RoutingDecision decision;
        decision.action = (venue == VenueType::INTERNAL) ?
                         RoutingAction::ROUTE_INTERNAL : RoutingAction::ROUTE_EXTERNAL;
        decision.primaryVenue = venue;
        decision.quantity = qty;
        decision.estimatedFillTimeNanos = fillTime;
        return decision;
    }

    /**
     * Create a rejected routing decision
     */
    static RoutingDecision rejected(const std::string& reason) {
        RoutingDecision decision;
        decision.action = RoutingAction::REJECT;
        decision.rejectReason = reason;
        return decision;
    }
};

} // namespace sor
} // namespace microoptimus

