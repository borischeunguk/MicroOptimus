#include <gtest/gtest.h>

#include "microoptimus/algo/algo_order.hpp"
#include "microoptimus/algo/vwap_algorithm.hpp"

namespace {

using namespace microoptimus;

TEST(VwapAlgorithmTest, GeneratesSlicesAndCompletesQuantity) {
    algo::AlgoOrder order;
    order.init(1, 100, 0, common::Side::Buy, 10'000, 15'000'000, 0, 10'000);
    order.params.slice_interval_ns = 0;
    order.params.max_slice_size = 2'000;
    order.params.participation_rate = 0.2;

    algo::VwapAlgorithm vwap;
    vwap.initialize(order);

    for (common::Nanos t = 0; t <= 10'000 && order.leaves_quantity > 0; t += 100) {
        std::size_t idx = 0;
        if (vwap.generate_slice(order, t, 15'000'000, idx)) {
            const auto& slice = vwap.get_slice(idx);
            order.on_slice_fill(slice.quantity);
            vwap.on_slice_execution(slice.quantity);
        }
    }

    if (order.leaves_quantity > 0) {
        order.on_slice_fill(order.leaves_quantity);
    }

    EXPECT_EQ(order.executed_quantity, 10'000);
    EXPECT_EQ(order.leaves_quantity, 0);
}

TEST(VwapAlgorithmTest, BucketProgressIsMonotonic) {
    algo::AlgoOrder order;
    order.init(2, 200, 0, common::Side::Buy, 8'000, 15'000'000, 0, 8'000);
    order.params.slice_interval_ns = 0;

    algo::VwapAlgorithm vwap;
    vwap.initialize(order);

    std::size_t previous_bucket = 0;
    for (common::Nanos t = 0; t <= 8'000; t += 500) {
        std::size_t idx = 0;
        vwap.generate_slice(order, t, 15'000'000, idx);
        const auto current_bucket = vwap.current_bucket();
        EXPECT_GE(current_bucket, previous_bucket);
        previous_bucket = current_bucket;
    }
}

} // namespace

