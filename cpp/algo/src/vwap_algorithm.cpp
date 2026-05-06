#include "microoptimus/algo/vwap_algorithm.hpp"

#include <algorithm>

namespace microoptimus::algo {

void VwapAlgorithm::initialize(const AlgoOrder& order) {
    num_buckets_ = std::min<std::size_t>(order.params.num_buckets, common::MAX_BUCKETS);
    current_bucket_ = 0;
    last_slice_time_ = 0;
    pool_index_ = 0;
    next_slice_id_ = 1;
    target_by_bucket_.fill(0);
    executed_by_bucket_.fill(0);
    volume_profile_.fill(0.0);

    const auto total_duration = order.end_time > order.start_time ? order.end_time - order.start_time : 1;
    bucket_duration_ = num_buckets_ > 0 ? total_duration / num_buckets_ : total_duration;

    create_default_volume_profile();

    common::Quantity assigned = 0;
    for (std::size_t i = 0; i + 1 < num_buckets_; ++i) {
        target_by_bucket_[i] = static_cast<common::Quantity>(
            static_cast<double>(order.total_quantity) * volume_profile_[i]);
        assigned += target_by_bucket_[i];
    }
    if (num_buckets_ > 0) {
        target_by_bucket_[num_buckets_ - 1] = order.total_quantity > assigned ? order.total_quantity - assigned : 0;
    }

    initialized_ = true;
}

void VwapAlgorithm::create_default_volume_profile() {
    if (num_buckets_ == 0) {
        return;
    }
    if (num_buckets_ == 1) {
        volume_profile_[0] = 1.0;
        return;
    }

    double total = 0.0;
    for (std::size_t i = 0; i < num_buckets_; ++i) {
        const double position = static_cast<double>(i) / static_cast<double>(num_buckets_ - 1);
        const double value = 1.0 + 0.5 * (2.0 * position - 1.0) * (2.0 * position - 1.0);
        volume_profile_[i] = value;
        total += value;
    }

    for (std::size_t i = 0; i < num_buckets_; ++i) {
        volume_profile_[i] /= total;
    }
}

void VwapAlgorithm::update_current_bucket(const AlgoOrder& order, common::Nanos now) {
    if (bucket_duration_ == 0 || num_buckets_ == 0) {
        current_bucket_ = 0;
        return;
    }

    const auto elapsed = now > order.start_time ? now - order.start_time : 0;
    const auto bucket = static_cast<std::size_t>(elapsed / bucket_duration_);
    current_bucket_ = std::min(bucket, num_buckets_ - 1);
}

bool VwapAlgorithm::should_generate_slice(const AlgoOrder& order, common::Nanos now) {
    if (!initialized_ || order.leaves_quantity == 0 || now < order.start_time || now > order.end_time) {
        return false;
    }

    update_current_bucket(order, now);

    if (last_slice_time_ != 0 && now - last_slice_time_ < order.params.slice_interval_ns) {
        return false;
    }

    const auto remaining = target_by_bucket_[current_bucket_] > executed_by_bucket_[current_bucket_]
        ? target_by_bucket_[current_bucket_] - executed_by_bucket_[current_bucket_]
        : 0;
    return remaining > 0;
}

bool VwapAlgorithm::generate_slice(AlgoOrder& order,
                                   common::Nanos current_time,
                                   common::Price current_price,
                                   std::size_t& out_index) {
    if (!should_generate_slice(order, current_time)) {
        return false;
    }

    common::Quantity target_by_now = 0;
    for (std::size_t i = 0; i <= current_bucket_; ++i) {
        target_by_now += target_by_bucket_[i];
    }

    const auto deficit = target_by_now > order.executed_quantity ? target_by_now - order.executed_quantity : 0;
    common::Quantity slice_size = deficit > 0
        ? std::min(deficit, order.params.max_slice_size)
        : std::min(target_by_bucket_[current_bucket_] - executed_by_bucket_[current_bucket_],
                   order.params.max_slice_size);

    slice_size = std::max(slice_size, order.params.min_slice_size);
    slice_size = std::min(slice_size, order.leaves_quantity);

    slice_size = static_cast<common::Quantity>(
        static_cast<double>(slice_size) * order.params.participation_rate * 10.0);
    slice_size = std::min(slice_size, order.params.max_slice_size);
    slice_size = std::min(slice_size, order.leaves_quantity);

    if (slice_size == 0) {
        return false;
    }

    out_index = pool_index_;
    pool_index_ = (pool_index_ + 1) % kMaxSlices;

    Slice& slice = slices_[out_index];
    slice.slice_id = next_slice_id_++;
    slice.side = order.side;
    slice.quantity = slice_size;
    slice.price = current_price;

    last_slice_time_ = current_time;
    return true;
}

void VwapAlgorithm::on_slice_execution(common::Quantity qty) {
    if (current_bucket_ < executed_by_bucket_.size()) {
        executed_by_bucket_[current_bucket_] += qty;
    }
}

} // namespace microoptimus::algo

