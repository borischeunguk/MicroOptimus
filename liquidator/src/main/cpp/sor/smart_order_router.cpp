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

// Thread-local storage for venue scorer cache
thread_local boost::container::small_vector<std::pair<VenueType, double>, 8> VenueScorer::score_cache_;

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

// Main Smart Order Router class
class SmartOrderRouter {
private:
    VenueScorer venueScorer_;
    RiskManager riskManager_;
    OrderSplitter orderSplitter_;

    // Configuration
    bool initialized_ = false;
    std::string configPath_;
    std::string sharedMemoryPath_;

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
