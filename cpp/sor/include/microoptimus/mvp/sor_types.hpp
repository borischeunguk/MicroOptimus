#pragma once

#include <cstdint>

#include "microoptimus/common/types.hpp"

namespace microoptimus::mvp {

enum class RoutingAction : std::uint8_t {
    RouteExternal = 0,
    RouteInternal = 1,
    SplitOrder = 2,
    Reject = 3,
};

struct OrderRequest {
    std::uint64_t sequence_id = 0;
    std::uint64_t order_id = 0;
    std::uint64_t client_id = 0;
    std::uint64_t parent_order_id = 0;
    std::uint32_t symbol_index = 0;
    common::Side side = common::Side::Buy;
    common::OrderType order_type = common::OrderType::Limit;
    common::Price price = 0;
    common::Quantity quantity = 0;
    common::TimeInForce time_in_force = common::TimeInForce::Ioc;
    common::OrderFlowType flow_type = common::OrderFlowType::AlgoSlice;
    common::Nanos timestamp = 0;
};

struct VenueConfig {
    common::VenueId venue = common::VenueId::Cme;
    std::int32_t priority = 0;
    bool enabled = true;
    common::Quantity max_order_size = 0;
    common::Nanos avg_latency_ns = 0;
    double fill_rate = 0.0;
    double fee_rate = 0.0;
};

struct RoutingDecision {
    RoutingAction action = RoutingAction::Reject;
    common::VenueId venue = common::VenueId::Internal;
    common::Quantity quantity = 0;
};

} // namespace microoptimus::mvp

