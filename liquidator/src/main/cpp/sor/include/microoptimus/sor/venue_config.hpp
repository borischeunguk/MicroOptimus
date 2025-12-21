#pragma once

#include "types.hpp"
#include <atomic>
#include <cstdint>

namespace microoptimus {
namespace sor {

/**
 * Venue configuration with performance counters
 */
struct VenueConfig {
    VenueType venueType;
    int priority;
    bool enabled;
    int64_t maxOrderSize;
    int64_t avgLatencyNanos;
    int64_t fillRate;        // Scaled by 1M for precision (e.g., 950000 = 95%)
    int64_t feesPerShare;    // Scaled by 1M for precision

    // Performance counters (mutable for const access)
    mutable std::atomic<uint64_t> ordersRouted{0};
    mutable std::atomic<uint64_t> ordersRejected{0};
    mutable std::atomic<uint64_t> totalLatencyNanos{0};

    VenueConfig() = default;

    VenueConfig(VenueType vt, int prio, bool en, int64_t maxSize,
               int64_t latency, int64_t fill, int64_t fees)
        : venueType(vt), priority(prio), enabled(en), maxOrderSize(maxSize),
          avgLatencyNanos(latency), fillRate(fill), feesPerShare(fees) {}

    // Copy constructor
    VenueConfig(const VenueConfig& other)
        : venueType(other.venueType), priority(other.priority), enabled(other.enabled),
          maxOrderSize(other.maxOrderSize), avgLatencyNanos(other.avgLatencyNanos),
          fillRate(other.fillRate), feesPerShare(other.feesPerShare) {
        ordersRouted.store(other.ordersRouted.load());
        ordersRejected.store(other.ordersRejected.load());
        totalLatencyNanos.store(other.totalLatencyNanos.load());
    }

    // Move constructor
    VenueConfig(VenueConfig&& other) noexcept
        : venueType(other.venueType), priority(other.priority), enabled(other.enabled),
          maxOrderSize(other.maxOrderSize), avgLatencyNanos(other.avgLatencyNanos),
          fillRate(other.fillRate), feesPerShare(other.feesPerShare) {
        ordersRouted.store(other.ordersRouted.load());
        ordersRejected.store(other.ordersRejected.load());
        totalLatencyNanos.store(other.totalLatencyNanos.load());
    }

    // Assignment operator
    VenueConfig& operator=(const VenueConfig& other) {
        if (this != &other) {
            venueType = other.venueType;
            priority = other.priority;
            enabled = other.enabled;
            maxOrderSize = other.maxOrderSize;
            avgLatencyNanos = other.avgLatencyNanos;
            fillRate = other.fillRate;
            feesPerShare = other.feesPerShare;
            ordersRouted.store(other.ordersRouted.load());
            ordersRejected.store(other.ordersRejected.load());
            totalLatencyNanos.store(other.totalLatencyNanos.load());
        }
        return *this;
    }
};

/**
 * Venue allocation for split orders
 */
struct VenueAllocation {
    VenueType venue;
    int64_t quantity;
    int priority;

    VenueAllocation(VenueType v, int64_t q, int p)
        : venue(v), quantity(q), priority(p) {}
};

} // namespace sor
} // namespace microoptimus

