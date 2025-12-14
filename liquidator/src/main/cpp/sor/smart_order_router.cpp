// Smart Order Router C++ Implementation
// Ultra-low latency routing with Boost and Folly integration

#include <jni.h>
#include <memory>
#include <vector>
#include <string>
#include <chrono>
#include <atomic>
#include <unordered_map>
#include <algorithm>

// Boost includes for high-performance containers and algorithms
#include <boost/container/flat_map.hpp>
#include <boost/container/small_vector.hpp>
#include <boost/algorithm/string.hpp>
#include <boost/lockfree/queue.hpp>

// Folly includes for Facebook's high-performance utilities
#include <folly/small_vector.h>
#include <folly/FBString.h>
#include <folly/Hash.h>
#include <folly/ThreadLocal.h>
#include <folly/Likely.h>

// Thread-local storage for performance
static thread_local std::chrono::high_resolution_clock::time_point tls_start_time;

namespace microoptimus {
namespace sor {

// Forward declarations
class VenueScorer;
class RiskManager;
class OrderSplitter;

// Enums matching Java side
enum class Side : int { BUY = 0, SELL = 1 };
enum class OrderType : int { MARKET = 0, LIMIT = 1, STOP = 2, STOP_LIMIT = 3 };
enum class VenueType : int { NONE = 0, INTERNAL = 1, CME = 2, NASDAQ = 3, NYSE = 4, ARCA = 5, IEX = 6 };
enum class RoutingAction : int { ROUTE_EXTERNAL = 0, ROUTE_INTERNAL = 1, SPLIT_ORDER = 2, REJECT = 3 };

// High-performance order structure
struct OrderRequest {
    int64_t orderId;
    folly::fbstring symbol;  // Facebook's optimized string
    Side side;
    OrderType orderType;
    int64_t price;
    int64_t quantity;
    int64_t timestamp;

    // Cache line padding for performance
    char padding[64 - sizeof(int64_t) * 4 - sizeof(folly::fbstring) - sizeof(Side) - sizeof(OrderType)];

    OrderRequest() = default;

    OrderRequest(int64_t id, const std::string& sym, Side s, OrderType ot,
                int64_t p, int64_t q, int64_t ts)
        : orderId(id), symbol(sym), side(s), orderType(ot),
          price(p), quantity(q), timestamp(ts) {}
};

// Venue configuration with performance metrics
struct VenueConfig {
    VenueType venueType;
    int priority;
    bool enabled;
    int64_t maxOrderSize;
    int64_t avgLatencyNanos;
    int64_t fillRate;        // Scaled by 1M for precision
    int64_t feesPerShare;    // Scaled by 1M for precision

    // Performance counters
    std::atomic<uint64_t> ordersRouted{0};
    std::atomic<uint64_t> ordersRejected{0};
    std::atomic<uint64_t> totalLatencyNanos{0};

    VenueConfig() = default;

    VenueConfig(VenueType vt, int prio, bool en, int64_t maxSize,
               int64_t latency, int64_t fill, int64_t fees)
        : venueType(vt), priority(prio), enabled(en), maxOrderSize(maxSize),
          avgLatencyNanos(latency), fillRate(fill), feesPerShare(fees) {}

    double getAverageLatency() const {
        uint64_t orders = ordersRouted.load();
        return orders > 0 ? (double)totalLatencyNanos.load() / orders : avgLatencyNanos;
    }
};

// Enhanced Venue TOB data structure for VWAP scenario support
struct VenueTOB {
    VenueType venueType;
    double bidPrice;
    double askPrice;
    int64_t bidQty;
    int64_t askQty;
    int64_t lastUpdateTime;
    int64_t avgLatencyNanos;
    int64_t fillRate;        // Scaled by 1M (e.g., 950000 = 95%)
    int64_t feesPerShare;    // Scaled by 1M (e.g., 2000 = $0.002)
    int64_t queuePosition;   // Estimated queue position
    int64_t totalVolume24h;  // 24h volume for liquidity assessment

    VenueTOB() = default;

    VenueTOB(VenueType vt, double bid, double ask, int64_t bidQ, int64_t askQ,
             int64_t latency, int64_t fill, int64_t fees, int64_t queue)
        : venueType(vt), bidPrice(bid), askPrice(ask), bidQty(bidQ), askQty(askQ),
          lastUpdateTime(0), avgLatencyNanos(latency), fillRate(fill),
          feesPerShare(fees), queuePosition(queue), totalVolume24h(0) {}

    double getFillRatePercent() const {
        return fillRate / 1000000.0;
    }

    double getFeesPerShareDollars() const {
        return feesPerShare / 1000000.0;
    }

    int64_t getLatencyMicros() const {
        return avgLatencyNanos / 1000;
    }

    bool isStale(int64_t maxAgeNanos) const {
        auto now = std::chrono::high_resolution_clock::now();
        auto nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch()).count();
        return (nowNanos - lastUpdateTime) > maxAgeNanos;
    }
};

// Enhanced venue snapshot for VWAP scenario
struct VenueSnapshot {
    folly::small_vector<VenueTOB, 8> venues;  // Support up to 8 venues efficiently
    int64_t snapshotTime;

    VenueSnapshot() {
        auto now = std::chrono::high_resolution_clock::now();
        snapshotTime = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch()).count();
    }

    void addVenue(const VenueTOB& venue) {
        venues.push_back(venue);
    }

    const VenueTOB* findVenue(VenueType venueType) const {
        for (const auto& venue : venues) {
            if (venue.venueType == venueType) {
                return &venue;
            }
        }
        return nullptr;
    }

    // Get venues sorted by ask price for buy orders
    folly::small_vector<VenueType, 8> getVenuesByBestAsk() const {
        folly::small_vector<std::pair<VenueType, double>, 8> priceVenues;

        for (const auto& venue : venues) {
            if (venue.askQty > 0) {  // Only venues with liquidity
                priceVenues.emplace_back(venue.venueType, venue.askPrice);
            }
        }

        // Sort by ask price (lowest first for buy orders)
        std::sort(priceVenues.begin(), priceVenues.end(),
            [](const auto& a, const auto& b) { return a.second < b.second; });

        folly::small_vector<VenueType, 8> result;
        for (const auto& pv : priceVenues) {
            result.push_back(pv.first);
        }
        return result;
    }
};

// VWAP slice request for large order handling
struct VWAPSliceRequest {
    int64_t sliceId;
    int64_t totalOrderId;
    folly::fbstring symbol;
    Side side;
    int64_t sliceQuantity;
    int64_t limitPrice;      // Scaled by 1M for precision
    int64_t maxLatencyNanos; // Maximum acceptable latency
    int64_t urgencyLevel;    // 1=urgent, 5=passive

    VWAPSliceRequest() = default;

    VWAPSliceRequest(int64_t sId, int64_t oId, const std::string& sym, Side s,
                     int64_t qty, int64_t limit, int64_t maxLat, int64_t urgency)
        : sliceId(sId), totalOrderId(oId), symbol(sym), side(s),
          sliceQuantity(qty), limitPrice(limit), maxLatencyNanos(maxLat), urgencyLevel(urgency) {}
};

// Enhanced venue scoring weights for VWAP scenario
struct ScoringWeights {
    double priceWeight = 0.40;      // 40% weight on price competitiveness
    double liquidityWeight = 0.25;  // 25% weight on available quantity
    double latencyWeight = 0.15;    // 15% weight on speed (negative factor)
    double fillRateWeight = 0.10;   // 10% weight on historical fill probability
    double feeWeight = 0.05;        // 5% weight on fees (negative factor)
    double queueWeight = 0.05;      // 5% weight on queue position (negative factor)

    // Urgency adjustments
    void adjustForUrgency(int64_t urgencyLevel) {
        if (urgencyLevel <= 2) {  // High urgency
            latencyWeight = 0.30;   // Prioritize speed
            priceWeight = 0.25;     // Reduce price sensitivity
            liquidityWeight = 0.30; // Prioritize available liquidity
            fillRateWeight = 0.10;
            feeWeight = 0.03;
            queueWeight = 0.02;
        } else if (urgencyLevel >= 4) {  // Low urgency
            priceWeight = 0.50;     // Prioritize best price
            liquidityWeight = 0.20;
            latencyWeight = 0.05;   // Speed less important
            fillRateWeight = 0.15;  // Historical performance important
            feeWeight = 0.07;       // Minimize costs
            queueWeight = 0.03;
        }
    }
};

// Venue allocation for split orders
struct VenueAllocation {
    VenueType venue;
    int64_t quantity;
    int priority;

    VenueAllocation(VenueType v, int64_t q, int p)
        : venue(v), quantity(q), priority(p) {}
};

// Routing decision result
struct RoutingDecision {
    RoutingAction action;
    VenueType primaryVenue;
    folly::small_vector<VenueAllocation, 4> allocations; // Most orders split to <=4 venues
    int64_t quantity;
    int64_t estimatedFillTimeNanos;
    std::string rejectReason;

    RoutingDecision() : action(RoutingAction::REJECT), primaryVenue(VenueType::NONE),
                       quantity(0), estimatedFillTimeNanos(0) {}

    static RoutingDecision singleVenue(VenueType venue, int64_t qty, int64_t fillTime) {
        RoutingDecision decision;
        decision.action = (venue == VenueType::INTERNAL) ?
                         RoutingAction::ROUTE_INTERNAL : RoutingAction::ROUTE_EXTERNAL;
        decision.primaryVenue = venue;
        decision.quantity = qty;
        decision.estimatedFillTimeNanos = fillTime;
        return decision;
    }

    static RoutingDecision splitOrder(const folly::small_vector<VenueAllocation, 4>& allocs,
                                     int64_t fillTime) {
        RoutingDecision decision;
        decision.action = RoutingAction::SPLIT_ORDER;
        decision.allocations = allocs;
        decision.primaryVenue = allocs.empty() ? VenueType::NONE : allocs[0].venue;
        decision.quantity = 0;
        for (const auto& alloc : allocs) {
            decision.quantity += alloc.quantity;
        }
        decision.estimatedFillTimeNanos = fillTime;
        return decision;
    }

    static RoutingDecision rejected(const std::string& reason) {
        RoutingDecision decision;
        decision.action = RoutingAction::REJECT;
        decision.rejectReason = reason;
        return decision;
    }
};

// Ultra-fast venue scorer using Boost containers
class VenueScorer {
private:
    // Flat map for O(log n) lookups with better cache locality than std::map
    boost::container::flat_map<VenueType, VenueConfig> venues_;

    // Thread-local scoring cache for hot path optimization
    static thread_local boost::container::small_vector<std::pair<VenueType, double>, 8> score_cache_;

public:
    void configureVenue(VenueType venue, const VenueConfig& config) {
        venues_[venue] = config;
    }

    // Ultra-fast venue scoring (target: <100ns)
    VenueType selectBestVenue(const OrderRequest& order) {
        score_cache_.clear();

        // Score all enabled venues
        for (const auto& [venueType, config] : venues_) {
            if (UNLIKELY(!config.enabled || order.quantity > config.maxOrderSize)) {
                continue;
            }

            double score = calculateVenueScore(config, order);
            score_cache_.emplace_back(venueType, score);
        }

        if (UNLIKELY(score_cache_.empty())) {
            return VenueType::NONE;
        }

        // Find best score using fast partial_sort
        auto best = std::max_element(score_cache_.begin(), score_cache_.end(),
            [](const auto& a, const auto& b) { return a.second < b.second; });

        return best->first;
    }

    // Get multiple venues for order splitting
    folly::small_vector<VenueType, 4> selectTopVenues(const OrderRequest& order, int maxVenues = 3) {
        score_cache_.clear();
        folly::small_vector<VenueType, 4> result;

        // Score and sort venues
        for (const auto& [venueType, config] : venues_) {
            if (config.enabled && order.quantity <= config.maxOrderSize) {
                double score = calculateVenueScore(config, order);
                score_cache_.emplace_back(venueType, score);
            }
        }

        // Partial sort to get top venues
        int numVenues = std::min(maxVenues, (int)score_cache_.size());
        std::partial_sort(score_cache_.begin(),
                         score_cache_.begin() + numVenues,
                         score_cache_.end(),
            [](const auto& a, const auto& b) { return a.second > b.second; });

        for (int i = 0; i < numVenues; i++) {
            result.push_back(score_cache_[i].first);
        }

        return result;
    }

    const VenueConfig* getVenueConfig(VenueType venue) const {
        auto it = venues_.find(venue);
        return (it != venues_.end()) ? &it->second : nullptr;
    }

private:
    // Multi-factor venue scoring algorithm
    double calculateVenueScore(const VenueConfig& config, const OrderRequest& order) {
        double score = config.priority; // Base priority

        // Latency penalty (lower latency = higher score)
        score -= config.getAverageLatency() / 1000.0; // Convert ns to penalty points

        // Fill rate bonus (higher fill rate = higher score)
        score += (config.fillRate / 1000000.0) * 50.0;

        // Fee penalty (lower fees = higher score)
        score -= (config.feesPerShare / 1000000.0) * order.quantity * 100.0;

        // Order size penalty for venues near limits
        double sizeRatio = (double)order.quantity / config.maxOrderSize;
        if (sizeRatio > 0.8) {
            score -= (sizeRatio - 0.8) * 20.0; // Penalty for large orders
        }

        // Market hours bonus (simplified - could check actual hours)
        if (config.venueType != VenueType::INTERNAL) {
            score += 5.0; // Slight bonus for external venues during market hours
        }

        return score;
    }
};

// Enhanced VenueScorer for VWAP scenario with TOB data support
class VWAPVenueScorer {
private:
    boost::container::flat_map<VenueType, VenueConfig> venueConfigs_;
    ScoringWeights scoringWeights_;

    // Thread-local cache for hot path optimization
    static thread_local boost::container::small_vector<std::pair<VenueType, double>, 8> vwap_score_cache_;

public:
    void configureVenue(VenueType venue, const VenueConfig& config) {
        venueConfigs_[venue] = config;
    }

    void updateScoringWeights(const ScoringWeights& weights) {
        scoringWeights_ = weights;
    }

    // Enhanced venue scoring for VWAP scenario - matches your exact requirements
    double calculateVWAPVenueScore(const VenueTOB& venue, const VWAPSliceRequest& slice) {
        double score = 0.0;

        // 1. Price Score (40% weight) - Higher score for better prices
        double priceScore = 0.0;
        if (slice.side == Side::BUY) {
            // For buy orders, lower ask price is better
            double priceImprovement = (double)slice.limitPrice - (venue.askPrice * 1000000.0);
            priceScore = std::max(0.0, priceImprovement / 10000.0); // Scale to reasonable range
        } else {
            // For sell orders, higher bid price is better
            double priceImprovement = (venue.bidPrice * 1000000.0) - (double)slice.limitPrice;
            priceScore = std::max(0.0, priceImprovement / 10000.0);
        }
        score += scoringWeights_.priceWeight * priceScore;

        // 2. Liquidity Score (25% weight) - Higher score for more available quantity
        int64_t availableQty = (slice.side == Side::BUY) ? venue.askQty : venue.bidQty;
        double liquidityScore = std::min(100.0, (double)availableQty / slice.sliceQuantity * 100.0);
        score += scoringWeights_.liquidityWeight * liquidityScore;

        // 3. Latency Score (15% weight) - Lower latency is better (negative factor)
        double latencyPenalty = venue.avgLatencyNanos / 1000000.0; // Convert to ms
        score -= scoringWeights_.latencyWeight * latencyPenalty;

        // 4. Fill Rate Score (10% weight) - Higher fill rate is better
        double fillScore = venue.getFillRatePercent();
        score += scoringWeights_.fillRateWeight * fillScore;

        // 5. Fee Score (5% weight) - Lower fees are better (negative factor)
        double feesPenalty = venue.getFeesPerShareDollars() * slice.sliceQuantity * 1000.0; // Scale up
        score -= scoringWeights_.feeWeight * feesPenalty;

        // 6. Queue Position Score (5% weight) - Better queue position is better (negative factor)
        double queuePenalty = venue.queuePosition / 10.0; // Scale queue position
        score -= scoringWeights_.queueWeight * queuePenalty;

        // Special bonuses for internal venue (matching your scenario requirements)
        if (venue.venueType == VenueType::INTERNAL) {
            score += 50.0; // Significant bonus for internal venue
        }

        // Urgency adjustments
        if (slice.urgencyLevel <= 2 && venue.avgLatencyNanos <= slice.maxLatencyNanos) {
            score += 20.0; // Bonus for fast venues when urgent
        }

        return score;
    }

    // Select best venues for VWAP slice allocation - implements your exact scenario
    folly::small_vector<VenueType, 4> selectVWAPVenues(const VenueSnapshot& snapshot,
                                                       const VWAPSliceRequest& slice,
                                                       int maxVenues = 4) {
        vwap_score_cache_.clear();

        // Adjust scoring weights based on urgency
        ScoringWeights adjustedWeights = scoringWeights_;
        adjustedWeights.adjustForUrgency(slice.urgencyLevel);

        // Score all venues in the snapshot
        for (const auto& venue : snapshot.venues) {
            if (!isVenueEligible(venue, slice)) {
                continue; // Skip venues that don't meet basic requirements
            }

            double score = calculateVWAPVenueScore(venue, slice);
            vwap_score_cache_.emplace_back(venue.venueType, score);
        }

        if (vwap_score_cache_.empty()) {
            return {};
        }

        // Sort by score (highest first)
        std::sort(vwap_score_cache_.begin(), vwap_score_cache_.end(),
            [](const auto& a, const auto& b) { return a.second > b.second; });

        // Return top venues up to maxVenues
        folly::small_vector<VenueType, 4> result;
        int numVenues = std::min(maxVenues, (int)vwap_score_cache_.size());
        for (int i = 0; i < numVenues; i++) {
            result.push_back(vwap_score_cache_[i].first);
        }

        return result;
    }

    // Allocate VWAP slice across selected venues - implements your allocation logic
    folly::small_vector<VenueAllocation, 4> allocateVWAPSlice(const VenueSnapshot& snapshot,
                                                              const VWAPSliceRequest& slice,
                                                              const folly::small_vector<VenueType, 4>& selectedVenues) {
        folly::small_vector<VenueAllocation, 4> allocations;

        if (selectedVenues.empty()) {
            return allocations;
        }

        int64_t remainingQty = slice.sliceQuantity;

        // Allocate to venues in score order, respecting available liquidity
        for (size_t i = 0; i < selectedVenues.size() && remainingQty > 0; i++) {
            const VenueTOB* venue = snapshot.findVenue(selectedVenues[i]);
            if (!venue) continue;

            // Determine available quantity for this venue
            int64_t availableQty = (slice.side == Side::BUY) ? venue->askQty : venue->bidQty;

            // Calculate allocation - prioritize better scoring venues
            int64_t allocation;
            if (i == selectedVenues.size() - 1) {
                // Last venue gets all remaining quantity
                allocation = std::min(remainingQty, availableQty);
            } else {
                // Earlier venues get preference based on liquidity and score
                double allocationRatio = std::min(1.0, (double)availableQty / slice.sliceQuantity);
                allocation = std::min(remainingQty,
                                    std::min(availableQty,
                                           (int64_t)(slice.sliceQuantity * allocationRatio * 0.6)));
            }

            if (allocation > 0) {
                allocations.emplace_back(selectedVenues[i], allocation, i + 1);
                remainingQty -= allocation;
            }
        }

        return allocations;
    }

private:
    bool isVenueEligible(const VenueTOB& venue, const VWAPSliceRequest& slice) {
        // Basic eligibility checks
        if (venue.isStale(5000000000LL)) { // 5 second staleness threshold
            return false;
        }

        int64_t availableQty = (slice.side == Side::BUY) ? venue.askQty : venue.bidQty;
        if (availableQty <= 0) {
            return false;
        }

        // Latency check for urgent orders
        if (slice.urgencyLevel <= 2 && venue.avgLatencyNanos > slice.maxLatencyNanos) {
            return false;
        }

        // Price check against limit
        if (slice.side == Side::BUY) {
            if (venue.askPrice * 1000000.0 > slice.limitPrice) {
                return false; // Ask price exceeds buy limit
            }
        } else {
            if (venue.bidPrice * 1000000.0 < slice.limitPrice) {
                return false; // Bid price below sell limit
            }
        }

        return true;
    }
};

// Thread-local storage for venue scorer caches
thread_local boost::container::small_vector<std::pair<VenueType, double>, 8> VenueScorer::score_cache_;
thread_local boost::container::small_vector<std::pair<VenueType, double>, 8> VWAPVenueScorer::vwap_score_cache_;

// Risk management with ultra-fast checks
class RiskManager {
private:
    // Risk limits
    int64_t maxOrderSize_ = 1000000;
    int64_t maxOrderValue_ = 100000000;
    double maxPriceDeviation_ = 0.1; // 10% from market

    // Symbol whitelist using Folly's fast hash
    folly::F14FastSet<folly::fbstring> allowedSymbols_;

    // Performance counters
    std::atomic<uint64_t> ordersChecked_{0};
    std::atomic<uint64_t> ordersRejected_{0};

public:
    bool passesRiskChecks(const OrderRequest& order) {
        ordersChecked_++;

        // Order size check
        if (UNLIKELY(order.quantity <= 0 || order.quantity > maxOrderSize_)) {
            ordersRejected_++;
            return false;
        }

        // Price reasonableness (simplified - in practice would check against market data)
        if (order.orderType == OrderType::LIMIT && order.price <= 0) {
            ordersRejected_++;
            return false;
        }

        // Symbol validation
        if (UNLIKELY(!allowedSymbols_.empty() &&
                    allowedSymbols_.find(order.symbol) == allowedSymbols_.end())) {
            ordersRejected_++;
            return false;
        }

        return true;
    }

    void addAllowedSymbol(const std::string& symbol) {
        allowedSymbols_.insert(symbol);
    }

    uint64_t getOrdersChecked() const { return ordersChecked_.load(); }
    uint64_t getOrdersRejected() const { return ordersRejected_.load(); }
};

// Order splitting logic
class OrderSplitter {
public:
    folly::small_vector<VenueAllocation, 4> splitOrder(
        const OrderRequest& order,
        const folly::small_vector<VenueType, 4>& venues,
        const VenueScorer& scorer) {

        folly::small_vector<VenueAllocation, 4> allocations;

        if (venues.empty()) {
            return allocations;
        }

        // Simple splitting algorithm - can be made more sophisticated
        if (venues.size() == 1) {
            allocations.emplace_back(venues[0], order.quantity, 1);
        } else {
            // Split based on venue capacities and priorities
            int64_t remainingQuantity = order.quantity;

            for (size_t i = 0; i < venues.size() && remainingQuantity > 0; i++) {
                const auto* config = scorer.getVenueConfig(venues[i]);
                if (!config) continue;

                // Calculate allocation (simple percentage-based for now)
                int64_t allocation;
                if (i == venues.size() - 1) {
                    // Last venue gets remaining quantity
                    allocation = remainingQuantity;
                } else {
                    // Earlier venues get larger shares
                    double share = 1.0 / (i + 1.5); // Decreasing share
                    allocation = std::min(remainingQuantity,
                                        (int64_t)(order.quantity * share));
                }

                if (allocation > 0) {
                    allocations.emplace_back(venues[i], allocation, i + 1);
                    remainingQuantity -= allocation;
                }
            }
        }

        return allocations;
    }
};

// Main Smart Order Router class with VWAP support
class SmartOrderRouter {
private:
    VenueScorer venueScorer_;          // Legacy scorer for simple orders
    VWAPVenueScorer vwapScorer_;       // Enhanced scorer for VWAP slices
    RiskManager riskManager_;
    OrderSplitter orderSplitter_;

    // Configuration
    bool initialized_ = false;
    std::string configPath_;
    std::string sharedMemoryPath_;
    std::string venueTOBPath_;         // Path to venue TOB shared memory

    // Performance statistics
    std::atomic<uint64_t> totalOrders_{0};
    std::atomic<uint64_t> internalRoutes_{0};
    std::atomic<uint64_t> externalRoutes_{0};
    std::atomic<uint64_t> rejectedOrders_{0};
    std::atomic<uint64_t> totalLatencyNanos_{0};
    std::atomic<uint64_t> maxLatencyNanos_{0};
    std::atomic<uint64_t> minLatencyNanos_{UINT64_MAX};

public:
    int initialize(const std::string& configPath, const std::string& sharedMemoryPath) {
        if (initialized_) {
            return 0; // Already initialized
        }

        configPath_ = configPath;
        sharedMemoryPath_ = sharedMemoryPath;

        // Initialize default venues
        initializeDefaultVenues();

        // Initialize risk manager with common symbols
        initializeRiskManager();

        initialized_ = true;
        return 0;
    }

    // Main routing method - optimized for sub-microsecond performance
    RoutingDecision routeOrder(const OrderRequest& order) {
        if (UNLIKELY(!initialized_)) {
            return RoutingDecision::rejected("SOR not initialized");
        }

        // Start timing
        auto start = std::chrono::high_resolution_clock::now();
        totalOrders_++;

        // Fast path: Risk checks first (fail fast)
        if (UNLIKELY(!riskManager_.passesRiskChecks(order))) {
            rejectedOrders_++;
            return RoutingDecision::rejected("Risk check failed");
        }

        RoutingDecision decision;

        // Routing logic based on order characteristics
        if (order.quantity <= 10000) {
            // Small orders: route to single best venue
            decision = routeSingleVenue(order);
        } else {
            // Large orders: consider splitting
            decision = routeWithSplitting(order);
        }

        // Update performance statistics
        updateLatencyStats(start);
        updateRoutingStats(decision);

        return decision;
    }

    // Enhanced VWAP slice routing - implements your exact scenario requirements
    RoutingDecision routeVWAPSlice(const VWAPSliceRequest& slice, const VenueSnapshot& snapshot) {
        if (UNLIKELY(!initialized_)) {
            return RoutingDecision::rejected("SOR not initialized");
        }

        // Start timing for sub-microsecond performance target
        auto start = std::chrono::high_resolution_clock::now();
        totalOrders_++;

        // Validate slice request
        if (slice.sliceQuantity <= 0 || slice.symbol.empty()) {
            rejectedOrders_++;
            return RoutingDecision::rejected("Invalid VWAP slice request");
        }

        // Select best venues using enhanced VWAP scoring
        auto selectedVenues = vwapScorer_.selectVWAPVenues(snapshot, slice, 4);

        if (selectedVenues.empty()) {
            rejectedOrders_++;
            return RoutingDecision::rejected("No suitable venues for VWAP slice");
        }

        // Allocate slice quantity across selected venues
        auto allocations = vwapScorer_.allocateVWAPSlice(snapshot, slice, selectedVenues);

        if (allocations.empty()) {
            rejectedOrders_++;
            return RoutingDecision::rejected("Failed to allocate VWAP slice");
        }

        // Create routing decision
        RoutingDecision decision;
        if (allocations.size() == 1) {
            // Single venue allocation
            decision = RoutingDecision::singleVenue(
                allocations[0].venue,
                allocations[0].quantity,
                estimateFillTime(allocations[0].venue, snapshot)
            );
        } else {
            // Multi-venue allocation (split order)
            decision = RoutingDecision::splitOrder(
                allocations,
                estimateMaxFillTime(allocations, snapshot)
            );
        }

        // Update performance statistics
        updateLatencyStats(start);
        updateRoutingStats(decision);

        return decision;
    }

    // Create venue snapshot from shared memory (for VWAP routing)
    VenueSnapshot createVenueSnapshot() {
        VenueSnapshot snapshot;

        // Example venue data matching your VWAP scenario
        // In production, this would read from VenueTOBStore shared memory

        // Internal venue (highest priority - 5μs latency, zero fees)
        snapshot.addVenue(VenueTOB(
            VenueType::INTERNAL,
            10.01,  // bid
            10.02,  // ask
            5000,   // bidQty
            3000,   // askQty
            5000,   // 5μs latency
            1000000,// 100% fill rate
            0,      // zero fees
            1       // best queue position
        ));

        // NASDAQ (competitive but higher latency and fees)
        snapshot.addVenue(VenueTOB(
            VenueType::NASDAQ,
            10.02,  // bid
            10.03,  // ask
            3000,   // bidQty
            2000,   // askQty
            50000,  // 50μs latency
            930000, // 93% fill rate
            2000,   // $0.002 fees
            5       // queue position
        ));

        // BATS (good price match)
        snapshot.addVenue(VenueTOB(
            VenueType::ARCA,  // Using ARCA enum for BATS
            10.01,  // bid
            10.02,  // ask
            2000,   // bidQty
            1000,   // askQty
            45000,  // 45μs latency
            930000, // 93% fill rate
            2000,   // $0.002 fees
            3       // queue position
        ));

        // ARCA (good latency)
        snapshot.addVenue(VenueTOB(
            VenueType::IEX,   // Using IEX enum for additional venue
            10.01,  // bid
            10.02,  // ask
            1000,   // bidQty
            500,    // askQty
            40000,  // 40μs latency
            910000, // 91% fill rate
            2000,   // $0.002 fees
            2       // queue position
        ));

        return snapshot;
    }

    bool configureVenue(VenueType venue, int priority, bool enabled,
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

    void getStatistics(int64_t* stats) {
        stats[0] = totalOrders_.load();
        stats[1] = internalRoutes_.load();
        stats[2] = externalRoutes_.load();
        stats[3] = rejectedOrders_.load();

        uint64_t total = totalOrders_.load();
        stats[4] = total > 0 ? totalLatencyNanos_.load() / total : 0; // avg
        stats[5] = maxLatencyNanos_.load();
        stats[6] = minLatencyNanos_.load() == UINT64_MAX ? 0 : minLatencyNanos_.load();
    }

    void shutdown() {
        initialized_ = false;
    }

private:
    void initializeDefaultVenues() {
        // Internal venue (highest priority)
        venueScorer_.configureVenue(VenueType::INTERNAL,
            VenueConfig(VenueType::INTERNAL, 100, true, 10000000, 0, 1000000, 0));

        // CME (high priority for futures)
        venueScorer_.configureVenue(VenueType::CME,
            VenueConfig(VenueType::CME, 90, true, 1000000, 150000, 950000, 100));

        // Nasdaq (good for equities)
        venueScorer_.configureVenue(VenueType::NASDAQ,
            VenueConfig(VenueType::NASDAQ, 85, true, 500000, 200000, 930000, 200));

        // NYSE (traditional equities)
        venueScorer_.configureVenue(VenueType::NYSE,
            VenueConfig(VenueType::NYSE, 80, true, 500000, 250000, 910000, 200));
    }

    void initializeRiskManager() {
        // Add common symbols (in practice, load from config)
        riskManager_.addAllowedSymbol("AAPL");
        riskManager_.addAllowedSymbol("GOOGL");
        riskManager_.addAllowedSymbol("MSFT");
        riskManager_.addAllowedSymbol("TSLA");
        riskManager_.addAllowedSymbol("ES"); // ES futures
        riskManager_.addAllowedSymbol("NQ"); // NQ futures
    }

    RoutingDecision routeSingleVenue(const OrderRequest& order) {
        VenueType bestVenue = venueScorer_.selectBestVenue(order);

        if (bestVenue == VenueType::NONE) {
            return RoutingDecision::rejected("No suitable venue");
        }

        const auto* config = venueScorer_.getVenueConfig(bestVenue);
        int64_t estimatedFillTime = config ? config->avgLatencyNanos : 50000; // 50µs default

        return RoutingDecision::singleVenue(bestVenue, order.quantity, estimatedFillTime);
    }

    RoutingDecision routeWithSplitting(const OrderRequest& order) {
        // Get top venues for splitting
        auto topVenues = venueScorer_.selectTopVenues(order, 3);

        if (topVenues.empty()) {
            return RoutingDecision::rejected("No venues available for split");
        }

        if (topVenues.size() == 1) {
            // Only one venue available, route single
            return routeSingleVenue(order);
        }

        // Split the order
        auto allocations = orderSplitter_.splitOrder(order, topVenues, venueScorer_);

        if (allocations.empty()) {
            return RoutingDecision::rejected("Order splitting failed");
        }

        // Estimate total fill time (max of all venues)
        int64_t maxFillTime = 0;
        for (const auto& alloc : allocations) {
            const auto* config = venueScorer_.getVenueConfig(alloc.venue);
            if (config) {
                maxFillTime = std::max(maxFillTime, config->avgLatencyNanos);
            }
        }

        return RoutingDecision::splitOrder(allocations, maxFillTime);
    }

    // Helper methods for VWAP fill time estimation
    int64_t estimateFillTime(VenueType venue, const VenueSnapshot& snapshot) {
        const VenueTOB* venueTOB = snapshot.findVenue(venue);
        return venueTOB ? venueTOB->avgLatencyNanos : 50000; // 50μs default
    }

    int64_t estimateMaxFillTime(const folly::small_vector<VenueAllocation, 4>& allocations,
                               const VenueSnapshot& snapshot) {
        int64_t maxFillTime = 0;
        for (const auto& alloc : allocations) {
            int64_t fillTime = estimateFillTime(alloc.venue, snapshot);
            maxFillTime = std::max(maxFillTime, fillTime);
        }
        return maxFillTime;
    }

    void updateLatencyStats(const std::chrono::high_resolution_clock::time_point& start) {
        auto end = std::chrono::high_resolution_clock::now();
        auto latency = std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count();

        totalLatencyNanos_ += latency;

        // Update min/max latency with atomic compare-and-swap
        uint64_t currentMax = maxLatencyNanos_.load();
        while (latency > currentMax &&
               !maxLatencyNanos_.compare_exchange_weak(currentMax, latency));

        uint64_t currentMin = minLatencyNanos_.load();
        while (latency < currentMin &&
               !minLatencyNanos_.compare_exchange_weak(currentMin, latency));
    }

    void updateRoutingStats(const RoutingDecision& decision) {
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
};

// Global SOR instance
static std::unique_ptr<SmartOrderRouter> g_smartOrderRouter;

} // namespace sor
} // namespace microoptimus

// JNI Interface Implementation
extern "C" {

using namespace microoptimus::sor;

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_initializeNative(
    JNIEnv* env, jobject obj, jstring configPath, jstring sharedMemoryPath) {

    try {
        const char* configPathStr = env->GetStringUTFChars(configPath, nullptr);
        const char* shmPathStr = env->GetStringUTFChars(sharedMemoryPath, nullptr);

        if (!g_smartOrderRouter) {
            g_smartOrderRouter = std::make_unique<SmartOrderRouter>();
        }

        int result = g_smartOrderRouter->initialize(
            std::string(configPathStr),
            std::string(shmPathStr)
        );

        env->ReleaseStringUTFChars(configPath, configPathStr);
        env->ReleaseStringUTFChars(sharedMemoryPath, shmPathStr);

        return result;

    } catch (const std::exception& e) {
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_routeOrderNative(
    JNIEnv* env, jobject obj, jlong orderId, jstring symbol, jint side, jint orderType,
    jlong price, jlong quantity, jlong timestamp, jobject resultBuffer) {

    if (!g_smartOrderRouter) {
        return -1;
    }

    try {
        // Convert Java parameters to C++
        const char* symbolStr = env->GetStringUTFChars(symbol, nullptr);

        OrderRequest order(
            orderId,
            std::string(symbolStr),
            static_cast<Side>(side),
            static_cast<OrderType>(orderType),
            price,
            quantity,
            timestamp
        );

        env->ReleaseStringUTFChars(symbol, symbolStr);

        // Route the order
        RoutingDecision decision = g_smartOrderRouter->routeOrder(order);

        // Write result to ByteBuffer
        void* bufferPtr = env->GetDirectBufferAddress(resultBuffer);
        if (bufferPtr) {
            int32_t* intBuffer = static_cast<int32_t*>(bufferPtr);
            int64_t* longBuffer = reinterpret_cast<int64_t*>(intBuffer + 4);

            // Write decision data
            intBuffer[0] = static_cast<int>(decision.action);
            intBuffer[1] = static_cast<int>(decision.primaryVenue);
            longBuffer[0] = decision.quantity;
            longBuffer[1] = decision.estimatedFillTimeNanos;

            // Write allocations if split order
            if (decision.action == RoutingAction::SPLIT_ORDER && !decision.allocations.empty()) {
                intBuffer[2] = decision.allocations.size();

                // Write allocations (venue, quantity, priority)
                for (size_t i = 0; i < decision.allocations.size() && i < 4; i++) {
                    size_t offset = 4 + 2 + i * 3; // Skip header data
                    if (offset + 2 < 64) { // Buffer safety check
                        intBuffer[offset] = static_cast<int>(decision.allocations[i].venue);
                        longBuffer[offset/2 + 1] = decision.allocations[i].quantity;
                        intBuffer[offset + 2] = decision.allocations[i].priority;
                    }
                }
            }
        }

        return decision.action == RoutingAction::REJECT ? -1 : 0;

    } catch (const std::exception& e) {
        return -2;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_routeVWAPSliceNative(
    JNIEnv* env, jobject obj, jlong sliceId, jlong totalOrderId, jstring symbol,
    jint side, jlong sliceQuantity, jlong limitPrice, jlong maxLatencyNanos,
    jint urgencyLevel, jobject resultBuffer) {

    if (!g_smartOrderRouter) {
        return -1;
    }

    try {
        // Convert Java parameters to C++ VWAP slice request
        const char* symbolStr = env->GetStringUTFChars(symbol, nullptr);

        VWAPSliceRequest slice(
            sliceId,
            totalOrderId,
            std::string(symbolStr),
            static_cast<Side>(side),
            sliceQuantity,
            limitPrice,
            maxLatencyNanos,
            urgencyLevel
        );

        env->ReleaseStringUTFChars(symbol, symbolStr);

        // Create venue snapshot (in production, this would read from shared memory)
        VenueSnapshot snapshot = g_smartOrderRouter->createVenueSnapshot();

        // Route the VWAP slice using enhanced algorithm
        RoutingDecision decision = g_smartOrderRouter->routeVWAPSlice(slice, snapshot);

        // Write result to ByteBuffer (same format as regular routing)
        void* bufferPtr = env->GetDirectBufferAddress(resultBuffer);
        if (bufferPtr) {
            int32_t* intBuffer = static_cast<int32_t*>(bufferPtr);
            int64_t* longBuffer = reinterpret_cast<int64_t*>(intBuffer + 4);

            // Write decision data
            intBuffer[0] = static_cast<int>(decision.action);
            intBuffer[1] = static_cast<int>(decision.primaryVenue);
            longBuffer[0] = decision.quantity;
            longBuffer[1] = decision.estimatedFillTimeNanos;

            // Write VWAP allocations if split order
            if (decision.action == RoutingAction::SPLIT_ORDER && !decision.allocations.empty()) {
                intBuffer[2] = decision.allocations.size();

                // Write venue allocations with quantities
                for (size_t i = 0; i < decision.allocations.size() && i < 4; i++) {
                    size_t offset = 4 + 2 + i * 3;
                    if (offset + 2 < 64) {
                        intBuffer[offset] = static_cast<int>(decision.allocations[i].venue);
                        longBuffer[offset/2 + 1] = decision.allocations[i].quantity;
                        intBuffer[offset + 2] = decision.allocations[i].priority;
                    }
                }
            }
        }

        return decision.action == RoutingAction::REJECT ? -1 : 0;

    } catch (const std::exception& e) {
        return -2;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_configureVenueNative(
    JNIEnv* env, jobject obj, jint venueId, jint priority, jint enabled,
    jlong maxOrderSize, jlong avgLatencyNanos, jlong fillRate, jlong feesPerShare) {

    if (!g_smartOrderRouter) {
        return -1;
    }

    try {
        bool result = g_smartOrderRouter->configureVenue(
            static_cast<VenueType>(venueId),
            priority,
            enabled != 0,
            maxOrderSize,
            avgLatencyNanos,
            fillRate,
            feesPerShare
        );

        return result ? 0 : -1;

    } catch (const std::exception& e) {
        return -2;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_getStatisticsNative(
    JNIEnv* env, jobject obj, jobject statsBuffer) {

    if (!g_smartOrderRouter) {
        return -1;
    }

    try {
        void* bufferPtr = env->GetDirectBufferAddress(statsBuffer);
        if (bufferPtr) {
            int64_t* stats = static_cast<int64_t*>(bufferPtr);
            g_smartOrderRouter->getStatistics(stats);
        }

        return 0;

    } catch (const std::exception& e) {
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_shutdownNative(
    JNIEnv* env, jobject obj) {

    if (g_smartOrderRouter) {
        g_smartOrderRouter->shutdown();
        g_smartOrderRouter.reset();
    }
}

} // extern "C"
