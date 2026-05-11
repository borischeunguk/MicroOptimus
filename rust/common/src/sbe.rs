use crate::types::Side;
use crate::wire::{ShmRef, SbeTemplateId};

/// Minimal fixed-layout codec helper used for SBE-style messages.
pub trait FixedCodec: Sized + Copy {
    fn template_id() -> SbeTemplateId;

    fn encode(&self) -> Vec<u8> {
        unsafe {
            std::slice::from_raw_parts(
                self as *const Self as *const u8,
                std::mem::size_of::<Self>(),
            )
            .to_vec()
        }
    }

    fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < std::mem::size_of::<Self>() {
            return None;
        }
        Some(unsafe { *(bytes.as_ptr() as *const Self) })
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct ParentOrderCommand {
    pub sequence_id: u64,
    pub parent_order_id: u64,
    pub client_id: u64,
    pub symbol_index: u32,
    pub side: u8,
    pub _pad: [u8; 3],
    pub total_quantity: u64,
    pub limit_price: u64,
    pub start_time: u64,
    pub end_time: u64,
    pub timestamp: u64,
    pub num_buckets: u32,
    pub participation_rate: f64,
    pub min_slice_size: u64,
    pub max_slice_size: u64,
    pub slice_interval_ns: u64,
}

impl ParentOrderCommand {
    pub fn side(&self) -> Side {
        match self.side {
            0 => Side::Buy,
            _ => Side::Sell,
        }
    }
}

impl FixedCodec for ParentOrderCommand {
    fn template_id() -> SbeTemplateId {
        SbeTemplateId::ParentOrderCommand
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct AlgoSliceRefEvent {
    pub sequence_id: u64,
    pub parent_order_id: u64,
    pub slice_id: u64,
    pub timestamp: u64,
    pub shm_ref: ShmRef,
}

impl FixedCodec for AlgoSliceRefEvent {
    fn template_id() -> SbeTemplateId {
        SbeTemplateId::AlgoSliceRefEvent
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct SorRouteRefEvent {
    pub sequence_id: u64,
    pub parent_order_id: u64,
    pub slice_id: u64,
    pub route_id: u64,
    pub timestamp: u64,
    pub shm_ref: ShmRef,
}

impl FixedCodec for SorRouteRefEvent {
    fn template_id() -> SbeTemplateId {
        SbeTemplateId::SorRouteRefEvent
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parent_order_roundtrip() {
        let msg = ParentOrderCommand {
            sequence_id: 11,
            parent_order_id: 1001,
            client_id: 7,
            symbol_index: 3,
            side: 0,
            total_quantity: 50_000,
            limit_price: 15_000_000,
            start_time: 100,
            end_time: 1_000,
            timestamp: 100,
            num_buckets: 12,
            participation_rate: 0.12,
            min_slice_size: 100,
            max_slice_size: 2_000,
            slice_interval_ns: 10,
            ..ParentOrderCommand::default()
        };

        let encoded = msg.encode();
        let decoded = ParentOrderCommand::decode(&encoded).unwrap();
        assert_eq!(decoded.parent_order_id, 1001);
        assert_eq!(decoded.side(), Side::Buy);
    }
}

