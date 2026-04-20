use common::types::*;

/// Order request for routing through the SOR.
///
/// Ported from Java `OrderRequest.java`.
#[derive(Clone, Copy)]
pub struct OrderRequest {
    pub sequence_id: u64,
    pub order_id: u64,
    pub client_id: u64,
    pub parent_order_id: u64,
    pub symbol_index: u32,
    pub side: Side,
    pub order_type: OrderType,
    pub price: u64,
    pub quantity: u64,
    pub time_in_force: TimeInForce,
    pub flow_type: OrderFlowType,
    pub timestamp: u64,
}

impl Default for OrderRequest {
    fn default() -> Self {
        Self {
            sequence_id: 0,
            order_id: 0,
            client_id: 0,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            order_type: OrderType::Limit,
            price: 0,
            quantity: 0,
            time_in_force: TimeInForce::Ioc,
            flow_type: OrderFlowType::Dma,
            timestamp: 0,
        }
    }
}

impl OrderRequest {
    pub fn is_dma(&self) -> bool {
        self.flow_type == OrderFlowType::Dma
    }

    pub fn is_principal(&self) -> bool {
        self.flow_type == OrderFlowType::Principal
    }

    pub fn is_algo_slice(&self) -> bool {
        self.flow_type == OrderFlowType::AlgoSlice
    }
}
