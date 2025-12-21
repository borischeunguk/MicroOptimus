#include "microoptimus/sor/risk_manager.hpp"

namespace microoptimus {
namespace sor {

bool RiskManager::passesRiskChecks(const OrderRequest& order) {
    ordersChecked_++;

    // Check quantity limits
    if (order.quantity <= 0 || order.quantity > maxOrderSize_) {
        ordersRejected_++;
        return false;
    }

    // Check price validity for limit orders and stop-limit orders
    if ((order.orderType == OrderType::LIMIT || order.orderType == OrderType::STOP_LIMIT)
        && order.price <= 0) {
        ordersRejected_++;
        return false;
    }

    // Additional risk checks can be added here:
    // - Position limits
    // - Exposure limits
    // - Rate limits
    // - Market hours checks

    return true;
}

} // namespace sor
} // namespace microoptimus

