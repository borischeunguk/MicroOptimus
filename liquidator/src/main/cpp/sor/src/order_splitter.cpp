#include "microoptimus/sor/order_splitter.hpp"
#include <algorithm>

namespace microoptimus {
namespace sor {

std::vector<VenueAllocation> OrderSplitter::splitOrder(
    const OrderRequest& order,
    const std::vector<VenueType>& venues,
    const VenueScorer& scorer) const {

    std::vector<VenueAllocation> allocations;

    if (venues.empty()) {
        return allocations;
    }

    // Single venue - no splitting needed
    if (venues.size() == 1) {
        allocations.emplace_back(venues[0], order.quantity, 1);
        return allocations;
    }

    // VWAP-style allocation: prioritize venues by score with capacity limits
    int64_t remainingQuantity = order.quantity;
    int priority = 1;

    for (const auto& venue : venues) {
        if (remainingQuantity <= 0) break;

        const VenueConfig* config = scorer.getVenueConfig(venue);
        if (!config || !config->enabled) continue;

        // Allocate min of: remaining quantity, venue max capacity, or proportional share
        int64_t venueCapacity = std::min(config->maxOrderSize, remainingQuantity);

        // VWAP-style proportional allocation
        int64_t allocation;
        if (priority == 1) {
            // Best venue gets 40% allocation
            allocation = std::min(venueCapacity, (remainingQuantity * 40) / 100);
        } else if (priority == 2) {
            // Second venue gets 30% allocation
            allocation = std::min(venueCapacity, (remainingQuantity * 30) / 100);
        } else {
            // Remaining venues split the rest proportionally
            int64_t remainingVenues = venues.size() - priority + 1;
            allocation = std::min(venueCapacity, remainingQuantity / remainingVenues);
        }

        // Ensure we allocate something if there's remaining quantity
        if (allocation == 0 && remainingQuantity > 0) {
            allocation = std::min(venueCapacity, remainingQuantity);
        }

        if (allocation > 0) {
            allocations.emplace_back(venue, allocation, priority);
            remainingQuantity -= allocation;
            priority++;
        }
    }

    // If still have remaining quantity, add to last allocation
    if (remainingQuantity > 0 && !allocations.empty()) {
        allocations.back().quantity += remainingQuantity;
    }

    return allocations;
}

} // namespace sor
} // namespace microoptimus

