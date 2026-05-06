#pragma once

#include "microoptimus/common/types.hpp"

namespace microoptimus::algo {

struct VwapParams {
    std::uint32_t num_buckets = 12;
    double participation_rate = 0.10;
    common::Quantity min_slice_size = 100;
    common::Quantity max_slice_size = 10'000;
    common::Nanos slice_interval_ns = 0;
};

struct AlgoOrder {
    std::uint64_t algo_order_id = 0;
    std::uint64_t client_id = 0;
    std::uint32_t symbol_index = 0;
    common::Side side = common::Side::Buy;
    common::Quantity total_quantity = 0;
    common::Quantity executed_quantity = 0;
    common::Quantity leaves_quantity = 0;
    common::Price limit_price = 0;
    common::Nanos start_time = 0;
    common::Nanos end_time = 0;
    VwapParams params{};

    void init(std::uint64_t order_id,
              std::uint64_t client,
              std::uint32_t symbol,
              common::Side side_value,
              common::Quantity total_qty,
              common::Price limit,
              common::Nanos start,
              common::Nanos end) {
        algo_order_id = order_id;
        client_id = client;
        symbol_index = symbol;
        side = side_value;
        total_quantity = total_qty;
        executed_quantity = 0;
        leaves_quantity = total_qty;
        limit_price = limit;
        start_time = start;
        end_time = end;
        params = VwapParams{};
    }

    void on_slice_fill(common::Quantity quantity) {
        executed_quantity += quantity;
        leaves_quantity = leaves_quantity > quantity ? leaves_quantity - quantity : 0;
    }
};

struct Slice {
    std::uint64_t slice_id = 0;
    common::Side side = common::Side::Buy;
    common::Quantity quantity = 0;
    common::Price price = 0;
};

} // namespace microoptimus::algo

