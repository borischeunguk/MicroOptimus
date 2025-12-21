#pragma once

#include <cstdint>
#include <string>

namespace microoptimus {
namespace sor {

/**
 * Order side enumeration
 */
enum class Side : int {
    BUY = 0,
    SELL = 1
};

/**
 * Order type enumeration
 */
enum class OrderType : int {
    MARKET = 0,
    LIMIT = 1,
    STOP = 2,
    STOP_LIMIT = 3
};

/**
 * Venue type enumeration
 */
enum class VenueType : int {
    NONE = 0,
    INTERNAL = 1,
    CME = 2,
    NASDAQ = 3,
    NYSE = 4,
    ARCA = 5,
    IEX = 6,
    BATS = 7
};

/**
 * Routing action enumeration
 */
enum class RoutingAction : int {
    ROUTE_EXTERNAL = 0,
    ROUTE_INTERNAL = 1,
    SPLIT_ORDER = 2,
    REJECT = 3
};

/**
 * Order request structure
 */
struct OrderRequest {
    int64_t orderId;
    std::string symbol;
    Side side;
    OrderType orderType;
    int64_t price;       // Scaled by 1M for precision
    int64_t quantity;
    int64_t timestamp;

    OrderRequest() = default;

    OrderRequest(int64_t id, const std::string& sym, Side s, OrderType ot,
                int64_t p, int64_t q, int64_t ts)
        : orderId(id), symbol(sym), side(s), orderType(ot),
          price(p), quantity(q), timestamp(ts) {}
};

} // namespace sor
} // namespace microoptimus

