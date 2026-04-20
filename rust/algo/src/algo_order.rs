use common::types::{AlgoOrderState, AlgorithmType, Side};

/// VWAP algorithm parameters inlined into the algo order.
#[derive(Clone, Copy)]
pub struct VwapParams {
    pub num_buckets: u32,
    pub participation_rate: f64,
    pub min_slice_size: u64,
    pub max_slice_size: u64,
    pub slice_interval_ns: u64,
}

impl Default for VwapParams {
    fn default() -> Self {
        Self {
            num_buckets: 10,
            participation_rate: 0.10,
            min_slice_size: 100,
            max_slice_size: 10_000,
            slice_interval_ns: 1_000_000_000,
        }
    }
}

/// Algo order - parent order that generates child slices.
///
/// Ported from Java `AlgoOrder.java`. Uses init/reset pool pattern.
pub struct AlgoOrder {
    pub algo_order_id: u64,
    pub client_id: u64,
    pub symbol_index: u32,
    pub side: Side,
    pub total_quantity: u64,
    pub limit_price: u64,
    pub algorithm_type: AlgorithmType,

    // Execution progress
    pub executed_quantity: u64,
    pub leaves_quantity: u64,
    pub avg_price: u64,
    pub slices_sent: u32,
    pub slices_filled: u32,
    pub slices_cancelled: u32,

    // Parameters
    pub params: VwapParams,

    // Timing
    pub start_time: u64,
    pub end_time: u64,
    pub create_timestamp: u64,
    pub last_update_timestamp: u64,

    // State
    pub state: AlgoOrderState,
}

impl Default for AlgoOrder {
    fn default() -> Self {
        Self {
            algo_order_id: 0,
            client_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            total_quantity: 0,
            limit_price: 0,
            algorithm_type: AlgorithmType::Vwap,
            executed_quantity: 0,
            leaves_quantity: 0,
            avg_price: 0,
            slices_sent: 0,
            slices_filled: 0,
            slices_cancelled: 0,
            params: VwapParams::default(),
            start_time: 0,
            end_time: 0,
            create_timestamp: 0,
            last_update_timestamp: 0,
            state: AlgoOrderState::Pending,
        }
    }
}

impl AlgoOrder {
    /// Initialize the order with fresh parameters.
    #[allow(clippy::too_many_arguments)]
    pub fn init(
        &mut self,
        algo_order_id: u64,
        client_id: u64,
        symbol_index: u32,
        side: Side,
        total_quantity: u64,
        limit_price: u64,
        algorithm_type: AlgorithmType,
        start_time: u64,
        end_time: u64,
        timestamp: u64,
    ) {
        self.algo_order_id = algo_order_id;
        self.client_id = client_id;
        self.symbol_index = symbol_index;
        self.side = side;
        self.total_quantity = total_quantity;
        self.limit_price = limit_price;
        self.algorithm_type = algorithm_type;
        self.executed_quantity = 0;
        self.leaves_quantity = total_quantity;
        self.avg_price = 0;
        self.slices_sent = 0;
        self.slices_filled = 0;
        self.slices_cancelled = 0;
        self.params = VwapParams::default();
        self.start_time = start_time;
        self.end_time = end_time;
        self.create_timestamp = timestamp;
        self.last_update_timestamp = timestamp;
        self.state = AlgoOrderState::Pending;
    }

    /// Reset for pool reuse.
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    /// Record a slice fill - updates VWAP price.
    pub fn on_slice_fill(&mut self, quantity: u64, price: u64, timestamp: u64) {
        let total_value = self.avg_price as u128 * self.executed_quantity as u128
            + price as u128 * quantity as u128;
        self.executed_quantity += quantity;
        self.leaves_quantity = self.leaves_quantity.saturating_sub(quantity);
        self.avg_price = if self.executed_quantity > 0 {
            (total_value / self.executed_quantity as u128) as u64
        } else {
            0
        };
        self.last_update_timestamp = timestamp;

        if self.leaves_quantity == 0 {
            self.state = AlgoOrderState::Filled;
        } else if self.executed_quantity > 0 {
            self.state = AlgoOrderState::PartialFill;
        }
    }

    pub fn on_slice_sent(&mut self) {
        self.slices_sent += 1;
    }

    pub fn on_slice_filled(&mut self) {
        self.slices_filled += 1;
    }

    pub fn on_slice_cancelled(&mut self) {
        self.slices_cancelled += 1;
    }

    // State transitions
    pub fn start(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Working;
        self.last_update_timestamp = timestamp;
    }

    pub fn pause(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Paused;
        self.last_update_timestamp = timestamp;
    }

    pub fn resume(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Working;
        self.last_update_timestamp = timestamp;
    }

    pub fn cancel(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Cancelled;
        self.last_update_timestamp = timestamp;
    }

    pub fn reject(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Rejected;
        self.last_update_timestamp = timestamp;
    }

    pub fn expire(&mut self, timestamp: u64) {
        self.state = AlgoOrderState::Expired;
        self.last_update_timestamp = timestamp;
    }

    pub fn is_active(&self) -> bool {
        self.state.is_active()
    }

    pub fn is_terminal(&self) -> bool {
        self.state.is_terminal()
    }

    pub fn completion_rate(&self) -> f64 {
        if self.total_quantity > 0 {
            self.executed_quantity as f64 / self.total_quantity as f64
        } else {
            0.0
        }
    }
}

impl common::pool::Poolable for AlgoOrder {
    fn reset(&mut self) {
        AlgoOrder::reset(self);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_order() -> AlgoOrder {
        let mut order = AlgoOrder::default();
        order.init(1, 100, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap, 1000, 2000, 999);
        order
    }

    #[test]
    fn test_order_init() {
        let order = make_order();
        assert_eq!(order.algo_order_id, 1);
        assert_eq!(order.total_quantity, 10_000);
        assert_eq!(order.leaves_quantity, 10_000);
        assert_eq!(order.executed_quantity, 0);
        assert_eq!(order.state, AlgoOrderState::Pending);
    }

    #[test]
    fn test_state_machine() {
        let mut order = make_order();
        assert!(order.state.can_start());

        order.start(1000);
        assert_eq!(order.state, AlgoOrderState::Working);
        assert!(order.state.can_generate_slices());

        order.pause(1100);
        assert_eq!(order.state, AlgoOrderState::Paused);
        assert!(!order.state.can_generate_slices());

        order.resume(1200);
        assert_eq!(order.state, AlgoOrderState::Working);

        order.cancel(1300);
        assert!(order.is_terminal());
    }

    #[test]
    fn test_slice_fill_vwap() {
        let mut order = make_order();
        order.start(1000);

        // First fill: 2000 @ 15_000_000
        order.on_slice_fill(2000, 15_000_000, 1100);
        assert_eq!(order.executed_quantity, 2000);
        assert_eq!(order.leaves_quantity, 8000);
        assert_eq!(order.avg_price, 15_000_000);
        assert_eq!(order.state, AlgoOrderState::PartialFill);

        // Second fill: 3000 @ 15_100_000
        // VWAP = (2000*15M + 3000*15.1M) / 5000 = (30B + 45.3B) / 5000 = 15_060_000
        order.on_slice_fill(3000, 15_100_000, 1200);
        assert_eq!(order.executed_quantity, 5000);
        assert_eq!(order.avg_price, 15_060_000);
    }

    #[test]
    fn test_order_filled() {
        let mut order = make_order();
        order.start(1000);
        order.on_slice_fill(10_000, 15_000_000, 1100);
        assert_eq!(order.state, AlgoOrderState::Filled);
        assert_eq!(order.leaves_quantity, 0);
        assert!((order.completion_rate() - 1.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_reset() {
        let mut order = make_order();
        order.start(1000);
        order.on_slice_fill(5000, 15_000_000, 1100);
        order.reset();
        assert_eq!(order.algo_order_id, 0);
        assert_eq!(order.state, AlgoOrderState::Pending);
        assert_eq!(order.executed_quantity, 0);
    }
}
