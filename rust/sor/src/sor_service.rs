use common::ipc::{IpcPublisher, IpcSubscriber};
use common::messages::{RoutingDecisionMsg, SliceMsg, VenueAllocationMsg, MAX_ALLOCATIONS};
use common::types::{OrderFlowType, OrderType, TimeInForce};

use crate::order_request::OrderRequest;
use crate::router::SmartOrderRouter;

/// SorService wraps SmartOrderRouter with IPC channels.
///
/// Reads slices from algo (IpcSubscriber), routes them,
/// and publishes routing decisions (IpcPublisher).
pub struct SorService<S: IpcSubscriber, P: IpcPublisher> {
    subscriber: S,
    publisher: P,
    router: SmartOrderRouter,
    slices_received: u64,
    decisions_published: u64,
}

impl<S: IpcSubscriber, P: IpcPublisher> SorService<S, P> {
    pub fn new(subscriber: S, publisher: P) -> Self {
        let mut router = SmartOrderRouter::new();
        router.initialize();

        Self {
            subscriber,
            publisher,
            router,
            slices_received: 0,
            decisions_published: 0,
        }
    }

    /// Poll for incoming slices and route them.
    ///
    /// Returns the number of decisions published.
    pub fn poll(&mut self) -> u64 {
        let mut published = 0u64;

        while let Some(data) = self.subscriber.poll() {
            if data.len() < std::mem::size_of::<SliceMsg>() {
                continue;
            }

            let slice_msg: &SliceMsg = unsafe { &*(data.as_ptr() as *const SliceMsg) };
            self.slices_received += 1;

            let request = OrderRequest {
                sequence_id: 0,
                order_id: slice_msg.slice_id,
                client_id: 0,
                parent_order_id: slice_msg.parent_order_id,
                symbol_index: slice_msg.symbol_index,
                side: slice_msg.side,
                order_type: OrderType::Limit,
                price: slice_msg.price,
                quantity: slice_msg.quantity,
                time_in_force: TimeInForce::Ioc,
                flow_type: OrderFlowType::AlgoSlice,
                timestamp: slice_msg.timestamp,
            };

            let decision = self.router.route_order(&request);

            // Encode routing decision
            let mut msg = RoutingDecisionMsg {
                order_id: decision.order_id,
                action: decision.action,
                primary_venue: decision
                    .primary_venue
                    .unwrap_or(common::types::VenueId::Internal),
                total_quantity: decision.quantity,
                timestamp: slice_msg.timestamp,
                ..RoutingDecisionMsg::default()
            };

            let num_allocs = decision.allocations.len().min(MAX_ALLOCATIONS);
            msg.num_allocations = num_allocs as u8;
            for (i, alloc) in decision.allocations.iter().take(num_allocs).enumerate() {
                msg.allocations[i] = VenueAllocationMsg {
                    venue_id: alloc.venue_id.unwrap_or(common::types::VenueId::Internal),
                    _pad: [0; 3],
                    quantity: alloc.quantity,
                    priority: alloc.priority,
                    _pad2: [0; 4],
                    estimated_latency_ns: alloc.estimated_latency_ns,
                    estimated_fill_probability: alloc.estimated_fill_probability,
                    estimated_cost: alloc.estimated_cost,
                };
            }

            let bytes: &[u8] = unsafe {
                std::slice::from_raw_parts(
                    &msg as *const RoutingDecisionMsg as *const u8,
                    std::mem::size_of::<RoutingDecisionMsg>(),
                )
            };

            if self.publisher.publish(bytes) {
                self.decisions_published += 1;
                published += 1;
            }
        }

        published
    }

    pub fn router(&self) -> &SmartOrderRouter {
        &self.router
    }

    pub fn router_mut(&mut self) -> &mut SmartOrderRouter {
        &mut self.router
    }

    pub fn slices_received(&self) -> u64 {
        self.slices_received
    }

    pub fn decisions_published(&self) -> u64 {
        self.decisions_published
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::ipc::MmapRingBuffer;
    use common::types::Side;

    #[test]
    fn test_sor_service_routes_slice() {
        let mut sub = MmapRingBuffer::new(4096);
        let pub_rb = MmapRingBuffer::new(4096);

        // Publish a SliceMsg to the subscriber's ring buffer
        let mut slice_msg = SliceMsg::default();
        slice_msg.slice_id = 1;
        slice_msg.parent_order_id = 100;
        slice_msg.symbol_index = 0;
        slice_msg.side = Side::Buy;
        slice_msg.quantity = 500;
        slice_msg.price = 15_000_000;
        slice_msg.timestamp = 1000;

        let bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                &slice_msg as *const SliceMsg as *const u8,
                std::mem::size_of::<SliceMsg>(),
            )
        };
        assert!(sub.publish(bytes));

        let mut service = SorService::new(sub, pub_rb);
        let published = service.poll();

        assert_eq!(published, 1);
        assert_eq!(service.slices_received(), 1);
        assert_eq!(service.decisions_published(), 1);
    }
}
