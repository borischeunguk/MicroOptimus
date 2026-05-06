#pragma once

#include <array>
#include <cstddef>

#include "microoptimus/algo/algo_order.hpp"
#include "microoptimus/common/types.hpp"

namespace microoptimus::algo {

class VwapAlgorithm {
public:
    static constexpr std::size_t kMaxSlices = 256;

    void initialize(const AlgoOrder& order);

    bool generate_slice(AlgoOrder& order,
                        common::Nanos current_time,
                        common::Price current_price,
                        std::size_t& out_index);

    const Slice& get_slice(std::size_t idx) const { return slices_[idx]; }
    void on_slice_execution(common::Quantity qty);
    std::size_t current_bucket() const { return current_bucket_; }

private:
    void create_default_volume_profile();
    void update_current_bucket(const AlgoOrder& order, common::Nanos now);
    bool should_generate_slice(const AlgoOrder& order, common::Nanos now);

    std::array<double, common::MAX_BUCKETS> volume_profile_{};
    std::array<common::Quantity, common::MAX_BUCKETS> target_by_bucket_{};
    std::array<common::Quantity, common::MAX_BUCKETS> executed_by_bucket_{};
    std::array<Slice, kMaxSlices> slices_{};

    std::size_t current_bucket_ = 0;
    std::size_t num_buckets_ = 0;
    common::Nanos bucket_duration_ = 0;
    common::Nanos last_slice_time_ = 0;
    std::size_t pool_index_ = 0;
    std::uint64_t next_slice_id_ = 1;
    bool initialized_ = false;
};

} // namespace microoptimus::algo

