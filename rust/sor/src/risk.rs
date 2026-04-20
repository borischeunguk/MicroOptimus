use crate::order_request::OrderRequest;
use common::types::OrderType;

/// Result of a risk check.
#[derive(Debug, Clone)]
pub enum RiskCheckResult {
    Approved,
    Rejected(&'static str),
}

impl RiskCheckResult {
    pub fn is_approved(&self) -> bool {
        matches!(self, RiskCheckResult::Approved)
    }
}

/// Basic risk manager for pre-trade order checks.
///
/// Ported from Java `RiskManager.java` (simplified for MVP).
pub struct RiskManager {
    pub max_order_size: u64,
    pub max_order_value: u64,
    pub min_order_size: u64,
}

impl Default for RiskManager {
    fn default() -> Self {
        Self {
            max_order_size: 1_000_000,
            max_order_value: 10_000_000_000, // $10M at price scale
            min_order_size: 1,
        }
    }
}

impl RiskManager {
    pub fn new() -> Self {
        Self::default()
    }

    /// Check an order against risk limits.
    pub fn check_order(&self, request: &OrderRequest) -> RiskCheckResult {
        if request.quantity < self.min_order_size {
            return RiskCheckResult::Rejected("Order size below minimum");
        }

        if request.quantity > self.max_order_size {
            return RiskCheckResult::Rejected("Order size exceeds maximum");
        }

        if request.order_type == OrderType::Limit {
            let order_value = request.price.saturating_mul(request.quantity);
            if order_value > self.max_order_value {
                return RiskCheckResult::Rejected("Order value exceeds maximum");
            }
        }

        RiskCheckResult::Approved
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::*;

    fn make_request(quantity: u64, price: u64) -> OrderRequest {
        OrderRequest {
            sequence_id: 1,
            order_id: 1,
            client_id: 1,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            order_type: OrderType::Limit,
            price,
            quantity,
            time_in_force: TimeInForce::Ioc,
            flow_type: OrderFlowType::Dma,
            timestamp: 0,
        }
    }

    #[test]
    fn test_approved_order() {
        let rm = RiskManager::new();
        let req = make_request(1000, 1_500); // 1000 * 1500 = 1.5M, well under 10B
        assert!(rm.check_order(&req).is_approved());
    }

    #[test]
    fn test_reject_too_large() {
        let rm = RiskManager::new();
        let req = make_request(2_000_000, 1_500);
        assert!(!rm.check_order(&req).is_approved());
    }

    #[test]
    fn test_reject_too_small() {
        let mut rm = RiskManager::new();
        rm.min_order_size = 100;
        let req = make_request(50, 1_500);
        assert!(!rm.check_order(&req).is_approved());
    }

    #[test]
    fn test_reject_value_too_high() {
        let rm = RiskManager::new();
        // 500_000 * 100_000 = 50B > 10B limit
        let req = make_request(500_000, 100_000);
        assert!(!rm.check_order(&req).is_approved());
    }

    #[test]
    fn test_market_order_skips_value_check() {
        let rm = RiskManager::new();
        let mut req = make_request(100_000, 100_000);
        req.order_type = OrderType::Market;
        // Market orders skip value check
        assert!(rm.check_order(&req).is_approved());
    }
}
