#pragma once

#include "types.hpp"
#include "venue_config.hpp"
#include "venue_scorer.hpp"
#include <vector>

namespace microoptimus {
namespace sor {

/**
 * Order splitter with VWAP-aware proportional allocation
 *
 * Allocation strategy:
 * - Best venue (highest score): 40% allocation
 * - Second venue: 30% allocation
 * - Remaining venues: proportional split
 * - Respects venue capacity limits
 */
class OrderSplitter {
public:
    OrderSplitter() = default;

    /**
     * Split order across multiple venues
     * @param order The order to split
     * @param venues Sorted list of venues (best first)
     * @param scorer Venue scorer for configuration lookup
     * @return Vector of venue allocations
     */
    std::vector<VenueAllocation> splitOrder(
        const OrderRequest& order,
        const std::vector<VenueType>& venues,
        const VenueScorer& scorer) const;
};

} // namespace sor
} // namespace microoptimus

