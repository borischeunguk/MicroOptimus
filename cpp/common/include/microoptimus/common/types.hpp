#pragma once

#include <cstddef>
#include <cstdint>

namespace microoptimus::common {

using Price = std::uint64_t;
using Quantity = std::uint64_t;
using Nanos = std::uint64_t;

constexpr std::size_t MAX_BUCKETS = 64;

enum class Side : std::uint8_t {
    Buy = 0,
    Sell = 1,
};

enum class OrderType : std::uint8_t {
    Market = 0,
    Limit = 1,
    Stop = 2,
    StopLimit = 3,
};

enum class TimeInForce : std::uint8_t {
    Ioc = 0,
    Gtc = 1,
    Day = 2,
};

enum class VenueId : std::uint8_t {
    Internal = 0,
    Cme = 1,
    Nasq = 2,
};

enum class AlgorithmType : std::uint8_t {
    Vwap = 0,
};

enum class OrderFlowType : std::uint8_t {
    Dma = 0,
    Principal = 1,
    AlgoSlice = 2,
};

} // namespace microoptimus::common

