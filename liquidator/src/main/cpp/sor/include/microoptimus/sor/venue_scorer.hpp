#pragma once

#include "types.hpp"
#include "venue_config.hpp"
#include <map>
#include <vector>

namespace microoptimus {
namespace sor {

/**
 * Venue scoring engine with VWAP-aware multi-factor algorithm
 *
 * Scoring factors:
 * - Priority: 40% (venue tier preference)
 * - Latency: 25% (execution speed)
 * - Fill Rate: 20% (probability of fill)
 * - Fees: 10% (cost optimization)
 * - Capacity: 5% (order size handling)
 * - Internal boost: +20% (internalization incentive)
 */
class VenueScorer {
public:
    VenueScorer() = default;

    /**
     * Configure a venue with its parameters
     */
    void configureVenue(VenueType venue, const VenueConfig& config);

    /**
     * Select the best single venue for an order
     */
    VenueType selectBestVenue(const OrderRequest& order) const;

    /**
     * Select top N venues for order splitting
     */
    std::vector<VenueType> selectTopVenues(const OrderRequest& order, int maxVenues = 3) const;

    /**
     * Get venue configuration
     */
    const VenueConfig* getVenueConfig(VenueType venue) const;

private:
    /**
     * Calculate venue score using multi-factor algorithm
     */
    double calculateVenueScore(const VenueConfig& config, const OrderRequest& order) const;

    std::map<VenueType, VenueConfig> venues_;
};

} // namespace sor
} // namespace microoptimus

