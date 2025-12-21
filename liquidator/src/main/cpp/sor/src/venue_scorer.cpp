#include "microoptimus/sor/venue_scorer.hpp"
#include <algorithm>

namespace microoptimus {
namespace sor {

void VenueScorer::configureVenue(VenueType venue, const VenueConfig& config) {
    venues_[venue] = config;
}

VenueType VenueScorer::selectBestVenue(const OrderRequest& order) const {
    VenueType bestVenue = VenueType::NONE;
    double bestScore = -1.0;

    // Validate order quantity
    if (order.quantity <= 0) {
        return VenueType::NONE;
    }

    for (const auto& [venueType, config] : venues_) {
        if (!config.enabled || order.quantity > config.maxOrderSize) {
            continue;
        }

        double score = calculateVenueScore(config, order);
        if (score > bestScore) {
            bestScore = score;
            bestVenue = venueType;
        }
    }

    return bestVenue;
}

std::vector<VenueType> VenueScorer::selectTopVenues(const OrderRequest& order, int maxVenues) const {
    std::vector<std::pair<VenueType, double>> scored_venues;

    for (const auto& [venueType, config] : venues_) {
        if (config.enabled && order.quantity <= config.maxOrderSize) {
            double score = calculateVenueScore(config, order);
            scored_venues.emplace_back(venueType, score);
        }
    }

    // Sort by score (descending)
    std::sort(scored_venues.begin(), scored_venues.end(),
             [](const auto& a, const auto& b) { return a.second > b.second; });

    std::vector<VenueType> result;
    int numVenues = std::min(maxVenues, static_cast<int>(scored_venues.size()));
    for (int i = 0; i < numVenues; i++) {
        result.push_back(scored_venues[i].first);
    }

    return result;
}

const VenueConfig* VenueScorer::getVenueConfig(VenueType venue) const {
    auto it = venues_.find(venue);
    return (it != venues_.end()) ? &it->second : nullptr;
}

double VenueScorer::calculateVenueScore(const VenueConfig& config, const OrderRequest& order) const {
    // VWAP-aware multi-factor scoring
    double score = 0.0;

    // 1. Priority weight (40%) - Venue preference
    score += config.priority * 0.4;

    // 2. Latency factor (25%) - Lower latency is better
    // Internal venue: ~5μs, External: 40-50μs
    double latencyScore = 100.0 - (config.avgLatencyNanos / 1000.0); // Convert to μs
    latencyScore = std::max(0.0, latencyScore); // Clamp to positive
    score += (latencyScore * 0.25);

    // 3. Fill rate factor (20%) - Higher fill rate is better
    double fillRateScore = (config.fillRate / 10000.0); // Scale to 0-100
    score += (fillRateScore * 0.20);

    // 4. Fee factor (10%) - Lower fees are better
    double totalFees = (config.feesPerShare / 1000000.0) * order.quantity;
    double feeScore = 100.0 - std::min(100.0, totalFees * 10.0);
    score += (feeScore * 0.10);

    // 5. Capacity factor (5%) - Can venue handle the order?
    double capacityScore = (order.quantity <= config.maxOrderSize) ? 100.0 : 0.0;
    score += (capacityScore * 0.05);

    // Internal venue boost: Give preference to internal liquidity
    if (config.venueType == VenueType::INTERNAL) {
        score *= 1.2; // 20% boost for internalization
    }

    return score;
}

} // namespace sor
} // namespace microoptimus

