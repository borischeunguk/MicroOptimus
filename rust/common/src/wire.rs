use crate::types::OrderFlowType;

/// Reference to payload bytes stored in a shared mmap region.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ShmRef {
    pub region_id: u32,
    pub msg_type: u16,
    pub _pad: u16,
    pub offset: u64,
    pub len: u32,
    pub _pad2: u32,
    pub seq: u64,
}

#[repr(u16)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SbeTemplateId {
    ParentOrderCommand = 1,
    AlgoSliceRefEvent = 2,
    SorRouteRefEvent = 3,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ClusterEnvelope {
    pub seq: u64,
    pub flow_type: u8,
    pub event_type: u8,
    pub _pad: u16,
    pub shm_ref: ShmRef,
}

impl ClusterEnvelope {
    pub fn flow(self) -> OrderFlowType {
        match self.flow_type {
            0 => OrderFlowType::Dma,
            1 => OrderFlowType::Principal,
            _ => OrderFlowType::AlgoSlice,
        }
    }
}

