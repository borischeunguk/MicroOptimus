#pragma once

#include "types.hpp"
#include "venue_config.hpp"
#include "routing_decision.hpp"
#include "venue_scorer.hpp"
#include "risk_manager.hpp"
#include "order_splitter.hpp"
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>

namespace microoptimus {
namespace sor {

/**
 * Smart Order Router - Production-grade routing engine
 *
 * Features:
 * - Ultra-low latency (<500ns target)
 * - Multi-factor venue scoring
 * - VWAP-aware order splitting
 * - Risk management
 * - Performance monitoring
 */
class SmartOrderRouter {
public:
    SmartOrderRouter();
    ~SmartOrderRouter() = default;

    /**
     * Initialize the SOR
     * @param configPath Path to configuration file
     * @param sharedMemoryPath Path to shared memory for venue data
     * @return 0 on success, negative on error
     */
    int initialize(const std::string& configPath, const std::string& sharedMemoryPath);

    /**
     * Route an order using VWAP-aware logic
     * @param order The order to route
     * @return Routing decision with venue allocations
     */
    RoutingDecision routeOrder(const OrderRequest& order);

    /**
     * Configure a venue dynamically
     */
    bool configureVenue(VenueType venue, int priority, bool enabled,
                       int64_t maxOrderSize, int64_t avgLatencyNanos,
                       int64_t fillRate, int64_t feesPerShare);

    /**
     * Get routing statistics
     * @param stats Array to fill with statistics [total, internal, external, rejected, avgLatency, maxLatency, minLatency]
     */
    void getStatistics(int64_t* stats) const;

    /**
     * Shutdown the SOR
     */
    void shutdown();

private:
    /**
     * Initialize default venue configurations
     */
    void initializeDefaultVenues();

    /**
     * Route to a single best venue
     */
    RoutingDecision routeSingleVenue(const OrderRequest& order);

    /**
     * Route with order splitting across multiple venues
     */
    RoutingDecision routeWithSplitting(const OrderRequest& order);

    /**
     * Update latency statistics
     */
    void updateLatencyStats(const std::chrono::high_resolution_clock::time_point& start);

    /**
     * Update routing statistics
     */
    void updateRoutingStats(const RoutingDecision& decision);

    // Core components
    VenueScorer venueScorer_;
    RiskManager riskManager_;
    OrderSplitter orderSplitter_;

    // State
    bool initialized_ = false;

    // Performance statistics
    std::atomic<uint64_t> totalOrders_{0};
    std::atomic<uint64_t> internalRoutes_{0};
    std::atomic<uint64_t> externalRoutes_{0};
    std::atomic<uint64_t> rejectedOrders_{0};
    std::atomic<uint64_t> totalLatencyNanos_{0};
    std::atomic<uint64_t> maxLatencyNanos_{0};
    std::atomic<uint64_t> minLatencyNanos_{UINT64_MAX};
};

} // namespace sor
} // namespace microoptimus

