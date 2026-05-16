use common::messages::MAX_BUCKETS;

use crate::algo_order::{AlgoOrder, VwapParams};
use crate::slice::Slice;

const MAX_SLICES: usize = 256;

/// VWAP Algorithm - Volume-Weighted Average Price execution.
///
/// Ported from Java `VWAPAlgorithm.java`.
///
/// Distributes execution across time buckets according to a volume profile,
/// with catch-up logic when behind schedule.
pub struct VwapAlgorithm {
    // Volume profile and bucket tracking
    volume_profile: [f64; MAX_BUCKETS],
    target_by_bucket: [u64; MAX_BUCKETS],
    executed_by_bucket: [u64; MAX_BUCKETS],
    current_bucket: usize,
    bucket_duration: u64,
    num_buckets: usize,

    // Slice pool
    slices: [Slice; MAX_SLICES],
    slice_pool_index: usize,
    next_slice_id: u64,

    // Timing
    last_slice_time: u64,
    slices_generated_count: u32,

    initialized: bool,
}

impl Default for VwapAlgorithm {
    fn default() -> Self {
        Self::new()
    }
}

impl VwapAlgorithm {
    pub fn new() -> Self {
        // Initialize slice pool
        let slices = std::array::from_fn(|_| Slice::default());

        Self {
            volume_profile: [0.0; MAX_BUCKETS],
            target_by_bucket: [0; MAX_BUCKETS],
            executed_by_bucket: [0; MAX_BUCKETS],
            current_bucket: 0,
            bucket_duration: 0,
            num_buckets: 0,
            slices,
            slice_pool_index: 0,
            next_slice_id: 1,
            last_slice_time: 0,
            slices_generated_count: 0,
            initialized: false,
        }
    }

    /// Initialize the algorithm for a new order.
    ///
    /// Creates volume profile and distributes target quantities across buckets.
    pub fn initialize(&mut self, order: &AlgoOrder) {
        let params = &order.params;
        self.num_buckets = params.num_buckets as usize;

        // Create U-shaped volume profile
        self.create_default_volume_profile();

        // Reset bucket tracking
        self.target_by_bucket = [0; MAX_BUCKETS];
        self.executed_by_bucket = [0; MAX_BUCKETS];
        self.current_bucket = 0;

        // Calculate bucket duration
        let total_duration = order.end_time.saturating_sub(order.start_time);
        self.bucket_duration = if self.num_buckets > 0 {
            total_duration / self.num_buckets as u64
        } else {
            total_duration
        };

        // Distribute quantity according to volume profile
        let total_qty = order.total_quantity;
        let mut assigned = 0u64;
        for i in 0..self.num_buckets.saturating_sub(1) {
            self.target_by_bucket[i] = (total_qty as f64 * self.volume_profile[i]) as u64;
            assigned += self.target_by_bucket[i];
        }
        if self.num_buckets > 0 {
            self.target_by_bucket[self.num_buckets - 1] = total_qty.saturating_sub(assigned);
        }

        self.last_slice_time = 0;
        self.slices_generated_count = 0;
        self.initialized = true;
    }

    /// Create U-shaped volume profile.
    /// Formula: `profile[i] = 1.0 + 0.5 * (2 * position - 1)^2`
    fn create_default_volume_profile(&mut self) {
        let n = self.num_buckets;
        if n < 2 {
            if n == 1 {
                self.volume_profile[0] = 1.0;
            }
            return;
        }

        let mut total = 0.0;
        for i in 0..n {
            let position = i as f64 / (n - 1) as f64;
            let val = 1.0 + 0.5 * (2.0 * position - 1.0).powi(2);
            self.volume_profile[i] = val;
            total += val;
        }
        for i in 0..n {
            self.volume_profile[i] /= total;
        }
    }

    /// Check whether it's time to generate a new slice.
    pub fn should_generate_slice(&mut self, order: &AlgoOrder, current_time: u64) -> bool {
        if !self.initialized {
            return false;
        }
        if current_time < order.start_time || current_time > order.end_time {
            return false;
        }
        if order.leaves_quantity == 0 {
            return false;
        }

        self.update_current_bucket(order, current_time);

        if self.current_bucket < self.num_buckets {
            let bucket_remaining = self.target_by_bucket[self.current_bucket]
                .saturating_sub(self.executed_by_bucket[self.current_bucket]);
            if bucket_remaining > 0 {
                return self.interval_elapsed(current_time, &order.params);
            }
        }

        false
    }

    /// Generate a slice if conditions are met. Returns the slice index in the pool, or None.
    pub fn generate_slice(
        &mut self,
        order: &mut AlgoOrder,
        current_time: u64,
        current_price: u64,
    ) -> Option<usize> {
        if !self.should_generate_slice(order, current_time) {
            return None;
        }

        let params = &order.params;

        // Calculate target by now (sum of all buckets up to current)
        let mut target_by_now = 0u64;
        for i in 0..=self.current_bucket {
            target_by_now += self.target_by_bucket[i];
        }

        // Calculate deficit
        let executed = order.executed_quantity;
        let deficit = target_by_now.saturating_sub(executed);

        // Determine slice size
        let mut slice_size = if deficit > 0 {
            // Behind schedule - larger slice
            deficit.min(params.max_slice_size)
        } else {
            // On or ahead of schedule - normal slice
            let bucket_remaining = self.target_by_bucket[self.current_bucket]
                .saturating_sub(self.executed_by_bucket[self.current_bucket]);
            bucket_remaining.min(params.max_slice_size)
        };

        slice_size = slice_size.max(params.min_slice_size);
        slice_size = slice_size.min(order.leaves_quantity);

        if slice_size == 0 {
            return None;
        }

        // Apply participation rate adjustment
        let participation = params.participation_rate;
        slice_size = (slice_size as f64 * participation * 10.0) as u64;
        slice_size = slice_size.min(params.max_slice_size);
        slice_size = slice_size.min(order.leaves_quantity);

        if slice_size == 0 {
            return None;
        }

        // Acquire slice from pool
        let idx = self.slice_pool_index;
        self.slice_pool_index = (self.slice_pool_index + 1) % MAX_SLICES;
        let slice_id = self.next_slice_id;
        self.next_slice_id += 1;

        self.slices[idx].init(
            slice_id,
            order.algo_order_id,
            order.symbol_index,
            order.side,
            slice_size,
            current_price,
            self.slices_generated_count + 1,
            current_time,
        );

        self.last_slice_time = current_time;
        self.slices_generated_count += 1;
        order.on_slice_sent();

        Some(idx)
    }

    /// Record execution against the current bucket.
    pub fn on_slice_execution(&mut self, exec_qty: u64) {
        if self.current_bucket < self.num_buckets {
            self.executed_by_bucket[self.current_bucket] += exec_qty;
        }
    }

    /// Check if the order is complete.
    pub fn is_order_complete(&self, order: &AlgoOrder) -> bool {
        order.leaves_quantity == 0 || order.is_terminal()
    }

    /// Estimate completion time based on remaining buckets.
    pub fn estimated_completion_time(&self, order: &AlgoOrder, current_time: u64) -> u64 {
        if order.leaves_quantity == 0 {
            return current_time;
        }
        let buckets_remaining = self.num_buckets.saturating_sub(self.current_bucket) as u64;
        current_time + buckets_remaining * self.bucket_duration
    }

    /// Get a reference to a slice by pool index.
    pub fn get_slice(&self, index: usize) -> &Slice {
        &self.slices[index]
    }

    /// Get a mutable reference to a slice by pool index.
    pub fn get_slice_mut(&mut self, index: usize) -> &mut Slice {
        &mut self.slices[index]
    }

    /// Reset algorithm state for reuse.
    pub fn reset(&mut self) {
        self.volume_profile = [0.0; MAX_BUCKETS];
        self.target_by_bucket = [0; MAX_BUCKETS];
        self.executed_by_bucket = [0; MAX_BUCKETS];
        self.current_bucket = 0;
        self.bucket_duration = 0;
        self.num_buckets = 0;
        self.last_slice_time = 0;
        self.slices_generated_count = 0;
        self.initialized = false;
    }

    // -- Private helpers --

    fn update_current_bucket(&mut self, order: &AlgoOrder, current_time: u64) {
        if self.bucket_duration == 0 {
            return;
        }
        let elapsed = current_time.saturating_sub(order.start_time);
        let new_bucket = (elapsed / self.bucket_duration) as usize;
        self.current_bucket = new_bucket.min(self.num_buckets.saturating_sub(1));
    }

    fn interval_elapsed(&self, current_time: u64, params: &VwapParams) -> bool {
        if self.last_slice_time == 0 {
            return true;
        }
        current_time.saturating_sub(self.last_slice_time) >= params.slice_interval_ns
    }

    pub fn current_bucket(&self) -> usize {
        self.current_bucket
    }

    pub fn num_buckets(&self) -> usize {
        self.num_buckets
    }

    pub fn target_by_bucket(&self) -> &[u64; MAX_BUCKETS] {
        &self.target_by_bucket
    }

    pub fn executed_by_bucket(&self) -> &[u64; MAX_BUCKETS] {
        &self.executed_by_bucket
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::{AlgorithmType, Side, SliceState};

    fn make_order(total_qty: u64, start: u64, end: u64) -> AlgoOrder {
        let mut order = AlgoOrder::default();
        order.init(
            1,
            100,
            0,
            Side::Buy,
            total_qty,
            15_000_000,
            AlgorithmType::Vwap,
            start,
            end,
            start,
        );
        order.params = VwapParams {
            num_buckets: 10,
            participation_rate: 0.10,
            min_slice_size: 100,
            max_slice_size: 10_000,
            slice_interval_ns: 1_000, // 1us for testing
        };
        order.start(start);
        order
    }

    #[test]
    fn test_initialize() {
        let order = make_order(10_000, 0, 10_000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        assert_eq!(vwap.num_buckets(), 10);
        assert_eq!(vwap.bucket_duration, 1000);

        // Target quantities should sum to total
        let total: u64 = vwap.target_by_bucket()[..10].iter().sum();
        assert_eq!(total, 10_000);
    }

    #[test]
    fn test_volume_profile_u_shape() {
        let order = make_order(10_000, 0, 10_000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        // Edge buckets should have more volume than center
        let n = vwap.num_buckets();
        assert!(vwap.target_by_bucket()[0] > vwap.target_by_bucket()[n / 2]);
        assert!(vwap.target_by_bucket()[n - 1] > vwap.target_by_bucket()[n / 2]);
    }

    #[test]
    fn test_should_not_generate_before_start() {
        let order = make_order(10_000, 1000, 2000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        assert!(!vwap.should_generate_slice(&order, 500));
    }

    #[test]
    fn test_should_not_generate_after_end() {
        let order = make_order(10_000, 1000, 2000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        assert!(!vwap.should_generate_slice(&order, 3000));
    }

    #[test]
    fn test_generate_slice() {
        let mut order = make_order(10_000, 0, 100_000);
        order.params.slice_interval_ns = 0; // no interval limit for test
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        let idx = vwap.generate_slice(&mut order, 100, 15_000_000);
        assert!(idx.is_some());

        let slice = vwap.get_slice(idx.unwrap());
        assert_eq!(slice.parent_algo_order_id, 1);
        assert!(slice.quantity > 0);
        assert_eq!(slice.price, 15_000_000);
        assert_eq!(slice.state, SliceState::Pending);
    }

    #[test]
    fn test_catch_up_logic() {
        let mut order = make_order(10_000, 0, 10_000);
        order.params.slice_interval_ns = 0;
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        // Move to bucket 5 without any execution -> should generate larger slice
        let idx = vwap.generate_slice(&mut order, 5000, 15_000_000);
        assert!(idx.is_some());

        let slice = vwap.get_slice(idx.unwrap());
        // Should try to catch up: slice size reflects deficit
        assert!(slice.quantity > 0);
    }

    #[test]
    fn test_bucket_tracking() {
        let order = make_order(10_000, 0, 10_000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        // At time 0 -> bucket 0
        vwap.should_generate_slice(&order, 0);
        assert_eq!(vwap.current_bucket(), 0);

        // At time 4500 -> bucket 4
        vwap.should_generate_slice(&order, 4500);
        assert_eq!(vwap.current_bucket(), 4);

        // At time 9999 -> bucket 9
        vwap.should_generate_slice(&order, 9999);
        assert_eq!(vwap.current_bucket(), 9);
    }

    #[test]
    fn test_is_order_complete() {
        let mut order = make_order(100, 0, 10_000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);

        assert!(!vwap.is_order_complete(&order));

        order.on_slice_fill(100, 15_000_000, 5000);
        assert!(vwap.is_order_complete(&order));
    }

    #[test]
    fn test_reset() {
        let order = make_order(10_000, 0, 10_000);
        let mut vwap = VwapAlgorithm::new();
        vwap.initialize(&order);
        assert!(vwap.initialized);

        vwap.reset();
        assert!(!vwap.initialized);
        assert_eq!(vwap.num_buckets(), 0);
    }
}
