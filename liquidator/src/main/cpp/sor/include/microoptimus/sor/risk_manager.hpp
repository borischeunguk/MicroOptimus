#pragma once

#include "types.hpp"
#include <atomic>
#include <cstdint>

namespace microoptimus {
namespace sor {

/**
 * Risk manager for pre-trade validation
 */
class RiskManager {
public:
    RiskManager() = default;

    /**
     * Perform risk checks on an order
     * @return true if order passes all risk checks
     */
    bool passesRiskChecks(const OrderRequest& order);

    /**
     * Set maximum order size limit
     */
    void setMaxOrderSize(int64_t maxSize) { maxOrderSize_ = maxSize; }

    /**
     * Get number of orders checked
     */
    uint64_t getOrdersChecked() const { return ordersChecked_.load(); }

    /**
     * Get number of orders rejected
     */
    uint64_t getOrdersRejected() const { return ordersRejected_.load(); }

private:
    int64_t maxOrderSize_ = 1000000;
    std::atomic<uint64_t> ordersChecked_{0};
    std::atomic<uint64_t> ordersRejected_{0};
};

} // namespace sor
} // namespace microoptimus

