use std::collections::HashMap;

use common::types::{AlgorithmType, Side};

use crate::algo_order::{AlgoOrder, VwapParams};
use crate::vwap::VwapAlgorithm;

const ORDER_POOL_SIZE: usize = 1024;

/// Generated slice info: (slice_id, parent_order_id, symbol_index, side, quantity, price, slice_number, timestamp).
pub type SliceInfo = (u64, u64, u32, Side, u64, u64, u32, u64);

/// AlgoEngine - Main orchestrator for algorithmic order execution.
///
/// Ported from Java `AlgoEngine.java`.
/// Manages order lifecycle, runs VWAP algorithm, generates slices.
pub struct AlgoEngine {
    // Active orders: order_id -> pool index
    active_orders: HashMap<u64, usize>,
    // Slice-to-order mapping: slice_id -> order_id
    slice_to_order: HashMap<u64, u64>,

    // Order pool
    order_pool: Vec<AlgoOrder>,
    order_pool_index: usize,

    // VWAP algorithm instance (only VWAP for MVP)
    vwap: VwapAlgorithm,

    // ID generator
    next_order_id: u64,

    // Statistics
    pub orders_received: u64,
    pub orders_completed: u64,
    pub slices_generated: u64,
    pub slices_filled: u64,
}

impl AlgoEngine {
    pub fn new() -> Self {
        let mut order_pool = Vec::with_capacity(ORDER_POOL_SIZE);
        for _ in 0..ORDER_POOL_SIZE {
            order_pool.push(AlgoOrder::default());
        }

        Self {
            active_orders: HashMap::new(),
            slice_to_order: HashMap::new(),
            order_pool,
            order_pool_index: 0,
            vwap: VwapAlgorithm::new(),
            next_order_id: 1,
            orders_received: 0,
            orders_completed: 0,
            slices_generated: 0,
            slices_filled: 0,
        }
    }

    /// Submit a new algo order. Returns the assigned order ID.
    #[allow(clippy::too_many_arguments)]
    pub fn submit_order(
        &mut self,
        client_id: u64,
        symbol_index: u32,
        side: Side,
        total_quantity: u64,
        limit_price: u64,
        algorithm_type: AlgorithmType,
        params: VwapParams,
        start_time: u64,
        end_time: u64,
        timestamp: u64,
    ) -> u64 {
        let order_id = self.next_order_id;
        self.next_order_id += 1;

        // Acquire from pool
        let pool_idx = self.order_pool_index;
        self.order_pool_index = (self.order_pool_index + 1) % ORDER_POOL_SIZE;

        let order = &mut self.order_pool[pool_idx];
        order.init(
            order_id,
            client_id,
            symbol_index,
            side,
            total_quantity,
            limit_price,
            algorithm_type,
            start_time,
            end_time,
            timestamp,
        );
        order.params = params;

        // Initialize algorithm
        self.vwap.initialize(order);

        self.active_orders.insert(order_id, pool_idx);
        self.orders_received += 1;

        order_id
    }

    /// Start an algo order.
    pub fn start_order(&mut self, order_id: u64, timestamp: u64) -> bool {
        if let Some(&pool_idx) = self.active_orders.get(&order_id) {
            let order = &mut self.order_pool[pool_idx];
            if order.state.can_start() {
                order.start(timestamp);
                return true;
            }
        }
        false
    }

    /// Pause an algo order.
    pub fn pause_order(&mut self, order_id: u64, timestamp: u64) -> bool {
        if let Some(&pool_idx) = self.active_orders.get(&order_id) {
            let order = &mut self.order_pool[pool_idx];
            if order.state.can_pause() {
                order.pause(timestamp);
                return true;
            }
        }
        false
    }

    /// Resume a paused order.
    pub fn resume_order(&mut self, order_id: u64, timestamp: u64) -> bool {
        if let Some(&pool_idx) = self.active_orders.get(&order_id) {
            let order = &mut self.order_pool[pool_idx];
            if order.state.can_resume() {
                order.resume(timestamp);
                return true;
            }
        }
        false
    }

    /// Cancel an algo order.
    pub fn cancel_order(&mut self, order_id: u64, timestamp: u64) -> bool {
        if let Some(&pool_idx) = self.active_orders.get(&order_id) {
            let order = &mut self.order_pool[pool_idx];
            if order.state.can_cancel() {
                order.cancel(timestamp);
                self.complete_order(order_id);
                return true;
            }
        }
        false
    }

    /// Process all active orders. Call periodically.
    ///
    /// Returns a Vec of (slice_id, parent_order_id, symbol_index, side, quantity, price, slice_number, timestamp)
    /// for each generated slice.
    pub fn process_orders(
        &mut self,
        current_time: u64,
        current_price: u64,
    ) -> Vec<SliceInfo> {
        let mut generated = Vec::new();
        let mut expired = Vec::new();

        // Collect order IDs to process
        let order_ids: Vec<u64> = self.active_orders.keys().copied().collect();

        for order_id in order_ids {
            let pool_idx = match self.active_orders.get(&order_id) {
                Some(&idx) => idx,
                None => continue,
            };

            let order = &self.order_pool[pool_idx];

            // Check expiration
            if current_time > order.end_time && !order.is_terminal() {
                expired.push(order_id);
                continue;
            }

            if !order.state.can_generate_slices() {
                continue;
            }

            // Generate slice
            let order = &mut self.order_pool[pool_idx];
            if let Some(slice_idx) = self.vwap.generate_slice(order, current_time, current_price) {
                let slice = self.vwap.get_slice(slice_idx);
                self.slice_to_order.insert(slice.slice_id, order_id);
                self.slices_generated += 1;

                generated.push((
                    slice.slice_id,
                    slice.parent_algo_order_id,
                    slice.symbol_index,
                    slice.side,
                    slice.quantity,
                    slice.price,
                    slice.slice_number,
                    slice.create_timestamp,
                ));

                // Check if order is complete
                let order = &self.order_pool[pool_idx];
                if self.vwap.is_order_complete(order) {
                    expired.push(order_id);
                }
            }
        }

        // Handle expired/completed orders
        for order_id in expired {
            if let Some(&pool_idx) = self.active_orders.get(&order_id) {
                let order = &mut self.order_pool[pool_idx];
                if !order.is_terminal() {
                    order.expire(current_time);
                }
            }
            self.complete_order(order_id);
        }

        generated
    }

    /// Handle slice execution feedback.
    pub fn on_slice_execution(
        &mut self,
        slice_id: u64,
        exec_qty: u64,
        exec_price: u64,
        timestamp: u64,
    ) {
        if let Some(&order_id) = self.slice_to_order.get(&slice_id) {
            if let Some(&pool_idx) = self.active_orders.get(&order_id) {
                let order = &mut self.order_pool[pool_idx];
                order.on_slice_fill(exec_qty, exec_price, timestamp);
                self.vwap.on_slice_execution(exec_qty);
                self.slices_filled += 1;

                if self.vwap.is_order_complete(order) {
                    self.complete_order(order_id);
                }
            }
        }
    }

    /// Handle slice completion.
    pub fn on_slice_complete(&mut self, slice_id: u64, filled: bool) {
        if let Some(&order_id) = self.slice_to_order.get(&slice_id) {
            if let Some(&pool_idx) = self.active_orders.get(&order_id) {
                let order = &mut self.order_pool[pool_idx];
                if filled {
                    order.on_slice_filled();
                } else {
                    order.on_slice_cancelled();
                }
            }
            self.slice_to_order.remove(&slice_id);
        }
    }

    /// Get an order by ID.
    pub fn get_order(&self, order_id: u64) -> Option<&AlgoOrder> {
        self.active_orders
            .get(&order_id)
            .map(|&idx| &self.order_pool[idx])
    }

    pub fn active_order_count(&self) -> usize {
        self.active_orders.len()
    }

    fn complete_order(&mut self, order_id: u64) {
        if let Some(pool_idx) = self.active_orders.remove(&order_id) {
            self.order_pool[pool_idx].reset();
            self.orders_completed += 1;
        }
    }
}

impl Default for AlgoEngine {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::AlgoOrderState;

    fn default_params() -> VwapParams {
        VwapParams {
            num_buckets: 10,
            participation_rate: 0.10,
            min_slice_size: 100,
            max_slice_size: 10_000,
            slice_interval_ns: 0, // no interval limit for tests
        }
    }

    #[test]
    fn test_submit_order() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        assert_eq!(id, 1);
        assert_eq!(engine.active_order_count(), 1);
        assert_eq!(engine.orders_received, 1);
    }

    #[test]
    fn test_start_order() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        assert!(engine.start_order(id, 0));
        let order = engine.get_order(id).unwrap();
        assert_eq!(order.state, AlgoOrderState::Working);
    }

    #[test]
    fn test_cancel_order() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        engine.start_order(id, 0);
        assert!(engine.cancel_order(id, 1000));
        assert_eq!(engine.active_order_count(), 0);
        assert_eq!(engine.orders_completed, 1);
    }

    #[test]
    fn test_process_orders_generates_slices() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        engine.start_order(id, 0);

        let slices = engine.process_orders(1000, 15_000_000);
        assert!(!slices.is_empty(), "should generate at least one slice");
        assert!(engine.slices_generated > 0);

        let (slice_id, parent_id, _, side, qty, price, _, _) = slices[0];
        assert!(slice_id > 0);
        assert_eq!(parent_id, id);
        assert_eq!(side, Side::Buy);
        assert!(qty > 0);
        assert_eq!(price, 15_000_000);
    }

    #[test]
    fn test_order_expiration() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 1_000, 0,
        );
        engine.start_order(id, 0);

        // Process at time past end_time
        let _ = engine.process_orders(2_000, 15_000_000);
        assert_eq!(engine.active_order_count(), 0);
        assert_eq!(engine.orders_completed, 1);
    }

    #[test]
    fn test_slice_execution_feedback() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 1_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        engine.start_order(id, 0);

        let slices = engine.process_orders(1000, 15_000_000);
        assert!(!slices.is_empty());

        let slice_id = slices[0].0;
        let slice_qty = slices[0].4;

        engine.on_slice_execution(slice_id, slice_qty, 15_000_000, 1500);
        assert!(engine.slices_filled > 0);
    }

    #[test]
    fn test_pause_resume() {
        let mut engine = AlgoEngine::new();
        let id = engine.submit_order(
            1, 0, Side::Buy, 10_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        engine.start_order(id, 0);

        assert!(engine.pause_order(id, 1000));
        assert_eq!(engine.get_order(id).unwrap().state, AlgoOrderState::Paused);

        // Should not generate slices when paused
        let slices = engine.process_orders(2000, 15_000_000);
        assert!(slices.is_empty());

        assert!(engine.resume_order(id, 3000));
        assert_eq!(engine.get_order(id).unwrap().state, AlgoOrderState::Working);
    }

    #[test]
    fn test_multiple_orders() {
        let mut engine = AlgoEngine::new();
        let id1 = engine.submit_order(
            1, 0, Side::Buy, 5_000, 15_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        let id2 = engine.submit_order(
            2, 1, Side::Sell, 3_000, 14_000_000, AlgorithmType::Vwap,
            default_params(), 0, 100_000, 0,
        );
        engine.start_order(id1, 0);
        engine.start_order(id2, 0);

        assert_eq!(engine.active_order_count(), 2);
        assert_eq!(engine.orders_received, 2);
    }
}
