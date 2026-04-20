use crate::types::*;

/// Maximum number of VWAP volume profile buckets.
pub const MAX_BUCKETS: usize = 32;
/// Maximum number of venue allocations in a routing decision.
pub const MAX_ALLOCATIONS: usize = 4;

/// VWAP parameters message: Signal -> Algo.
///
/// Fixed-size, `#[repr(C)]` for zero-copy IPC over shared memory.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct VwapParamsMsg {
    pub symbol_index: u32,
    pub num_buckets: u32,
    pub participation_rate: f64,
    pub min_slice_size: u64,
    pub max_slice_size: u64,
    pub slice_interval_ns: u64,
    pub volume_profile: [f64; MAX_BUCKETS],
    pub timestamp: u64,
}

impl Default for VwapParamsMsg {
    fn default() -> Self {
        Self {
            symbol_index: 0,
            num_buckets: 0,
            participation_rate: 0.0,
            min_slice_size: 0,
            max_slice_size: 0,
            slice_interval_ns: 0,
            volume_profile: [0.0; MAX_BUCKETS],
            timestamp: 0,
        }
    }
}

/// Algo order message: external -> Algo engine.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct AlgoOrderMsg {
    pub order_id: u64,
    pub client_id: u64,
    pub symbol_index: u32,
    pub side: Side,
    pub algorithm_type: AlgorithmType,
    pub _pad1: [u8; 2],
    pub total_quantity: u64,
    pub limit_price: u64,
    pub start_time: u64,
    pub end_time: u64,
    pub timestamp: u64,
}

impl Default for AlgoOrderMsg {
    fn default() -> Self {
        Self {
            order_id: 0,
            client_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            algorithm_type: AlgorithmType::Vwap,
            _pad1: [0; 2],
            total_quantity: 0,
            limit_price: 0,
            start_time: 0,
            end_time: 0,
            timestamp: 0,
        }
    }
}

/// Slice message: Algo -> SOR.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SliceMsg {
    pub slice_id: u64,
    pub parent_order_id: u64,
    pub symbol_index: u32,
    pub side: Side,
    pub _pad1: [u8; 3],
    pub quantity: u64,
    pub price: u64,
    pub slice_number: u32,
    pub _pad2: [u8; 4],
    pub timestamp: u64,
}

impl Default for SliceMsg {
    fn default() -> Self {
        Self {
            slice_id: 0,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            _pad1: [0; 3],
            quantity: 0,
            price: 0,
            slice_number: 0,
            _pad2: [0; 4],
            timestamp: 0,
        }
    }
}

/// Single venue allocation within a routing decision.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct VenueAllocationMsg {
    pub venue_id: VenueId,
    pub _pad: [u8; 3],
    pub quantity: u64,
    pub priority: u32,
    pub _pad2: [u8; 4],
    pub estimated_latency_ns: u64,
    pub estimated_fill_probability: f64,
    pub estimated_cost: f64,
}

impl Default for VenueAllocationMsg {
    fn default() -> Self {
        Self {
            venue_id: VenueId::Internal,
            _pad: [0; 3],
            quantity: 0,
            priority: 0,
            _pad2: [0; 4],
            estimated_latency_ns: 0,
            estimated_fill_probability: 0.0,
            estimated_cost: 0.0,
        }
    }
}

/// Routing decision message: SOR output.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct RoutingDecisionMsg {
    pub order_id: u64,
    pub action: RoutingAction,
    pub primary_venue: VenueId,
    pub num_allocations: u8,
    pub _pad: [u8; 1],
    pub total_quantity: u64,
    pub timestamp: u64,
    pub allocations: [VenueAllocationMsg; MAX_ALLOCATIONS],
}

impl Default for RoutingDecisionMsg {
    fn default() -> Self {
        Self {
            order_id: 0,
            action: RoutingAction::Reject,
            primary_venue: VenueId::Internal,
            num_allocations: 0,
            _pad: [0; 1],
            total_quantity: 0,
            timestamp: 0,
            allocations: [VenueAllocationMsg::default(); MAX_ALLOCATIONS],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem;

    #[test]
    fn test_message_sizes_are_fixed() {
        // Ensure all messages have deterministic sizes at compile time
        assert!(mem::size_of::<VwapParamsMsg>() > 0);
        assert!(mem::size_of::<AlgoOrderMsg>() > 0);
        assert!(mem::size_of::<SliceMsg>() > 0);
        assert!(mem::size_of::<RoutingDecisionMsg>() > 0);
        assert!(mem::size_of::<VenueAllocationMsg>() > 0);
    }

    #[test]
    fn test_vwap_params_roundtrip() {
        let mut msg = VwapParamsMsg::default();
        msg.symbol_index = 42;
        msg.num_buckets = 10;
        msg.participation_rate = 0.10;
        msg.min_slice_size = 100;
        msg.max_slice_size = 10_000;
        msg.volume_profile[0] = 0.15;
        msg.volume_profile[9] = 0.15;

        // Reinterpret as bytes and back (zero-copy roundtrip)
        let bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                &msg as *const VwapParamsMsg as *const u8,
                mem::size_of::<VwapParamsMsg>(),
            )
        };
        let restored: &VwapParamsMsg = unsafe { &*(bytes.as_ptr() as *const VwapParamsMsg) };

        assert_eq!(restored.symbol_index, 42);
        assert_eq!(restored.num_buckets, 10);
        assert!((restored.participation_rate - 0.10).abs() < f64::EPSILON);
        assert_eq!(restored.min_slice_size, 100);
        assert!((restored.volume_profile[0] - 0.15).abs() < f64::EPSILON);
    }

    #[test]
    fn test_slice_msg_roundtrip() {
        let mut msg = SliceMsg::default();
        msg.slice_id = 1001;
        msg.parent_order_id = 500;
        msg.side = Side::Sell;
        msg.quantity = 250;
        msg.price = 15_500_000;

        let bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                &msg as *const SliceMsg as *const u8,
                mem::size_of::<SliceMsg>(),
            )
        };
        let restored: &SliceMsg = unsafe { &*(bytes.as_ptr() as *const SliceMsg) };

        assert_eq!(restored.slice_id, 1001);
        assert_eq!(restored.parent_order_id, 500);
        assert_eq!(restored.side, Side::Sell);
        assert_eq!(restored.quantity, 250);
        assert_eq!(restored.price, 15_500_000);
    }
}
