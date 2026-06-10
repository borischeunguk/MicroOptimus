use common::cluster::{ClusterPublisher, ClusterSubscriber};
use common::messages::{RoutingDecisionMsg, SliceMsg, VenueAllocationMsg, MAX_ALLOCATIONS};
use common::sbe::{AlgoSliceRefEvent, FixedCodec, SorRouteRefEvent};
use common::shm::SharedRegion;
use common::types::{OrderFlowType, OrderType, TimeInForce, VenueId};

use crate::order_request::OrderRequest;
use crate::router::SmartOrderRouter;

/// Aeron-cluster-facing adapter for SOR.
pub struct SorClusterService<S: ClusterSubscriber, P: ClusterPublisher> {
    subscriber: S,
    publisher: P,
    router: SmartOrderRouter,
    route_seq: u64,
}

impl<S: ClusterSubscriber, P: ClusterPublisher> SorClusterService<S, P> {
    pub fn new(subscriber: S, publisher: P) -> Self {
        let mut router = SmartOrderRouter::new();
        router.initialize();
        Self { subscriber, publisher, router, route_seq: 1 }
    }

    /// Construct with a pre-configured router (e.g. benchmarks that need specific venue configs).
    pub fn new_with_router(subscriber: S, publisher: P, router: SmartOrderRouter) -> Self {
        Self { subscriber, publisher, router, route_seq: 1 }
    }

    pub fn poll(&mut self, region: &mut SharedRegion) -> u64 {
        let mut published = 0u64;

        while let Some(bytes) = self.subscriber.poll() {
            let Some(slice_ref_event) = AlgoSliceRefEvent::decode(&bytes) else {
                continue;
            };

            let Some(payload) = region.read(&slice_ref_event.shm_ref) else {
                continue;
            };
            if payload.len() < std::mem::size_of::<SliceMsg>() {
                continue;
            }

            let slice_msg: &SliceMsg = unsafe { &*(payload.as_ptr() as *const SliceMsg) };

            let request = OrderRequest {
                sequence_id: slice_ref_event.sequence_id,
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
            let mut decision_msg = RoutingDecisionMsg {
                order_id: decision.order_id,
                action: decision.action,
                primary_venue: decision.primary_venue.unwrap_or(VenueId::Internal),
                num_allocations: decision.allocations.len().min(MAX_ALLOCATIONS) as u8,
                total_quantity: decision.quantity,
                timestamp: request.timestamp,
                ..RoutingDecisionMsg::default()
            };

            for (i, alloc) in decision
                .allocations
                .iter()
                .take(decision_msg.num_allocations as usize)
                .enumerate()
            {
                decision_msg.allocations[i] = VenueAllocationMsg {
                    venue_id: alloc.venue_id.unwrap_or(VenueId::Internal),
                    _pad: [0; 3],
                    quantity: alloc.quantity,
                    priority: alloc.priority,
                    _pad2: [0; 4],
                    estimated_latency_ns: alloc.estimated_latency_ns,
                    estimated_fill_probability: alloc.estimated_fill_probability,
                    estimated_cost: alloc.estimated_cost,
                };
            }

            let decision_payload = unsafe {
                std::slice::from_raw_parts(
                    &decision_msg as *const RoutingDecisionMsg as *const u8,
                    std::mem::size_of::<RoutingDecisionMsg>(),
                )
            };
            let Some(shm_ref) = region.write(3, decision_payload) else {
                continue;
            };

            let out = SorRouteRefEvent {
                sequence_id: self.route_seq,
                parent_order_id: slice_ref_event.parent_order_id,
                slice_id: slice_ref_event.slice_id,
                route_id: decision.order_id,
                timestamp: request.timestamp,
                shm_ref,
            };
            self.route_seq += 1;

            if self.publisher.publish(&out.encode()) {
                published += 1;
            }
        }

        published
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::cluster::InMemoryClusterTopic;
    use common::types::Side;

    #[test]
    fn routes_slice_ref_events() {
        let in_topic = InMemoryClusterTopic::new();
        let out_topic = InMemoryClusterTopic::new();
        let mut publisher = in_topic.publisher();

        let mut region = SharedRegion::new_anon(1, 1 << 20);
        let slice = SliceMsg {
            slice_id: 77,
            parent_order_id: 55,
            symbol_index: 1,
            side: Side::Buy,
            quantity: 1_000,
            price: 15_000_000,
            slice_number: 1,
            timestamp: 100,
            ..SliceMsg::default()
        };
        let slice_payload = unsafe {
            std::slice::from_raw_parts(
                &slice as *const SliceMsg as *const u8,
                std::mem::size_of::<SliceMsg>(),
            )
        };
        let shm_ref = region.write(2, slice_payload).unwrap();
        let event = AlgoSliceRefEvent {
            sequence_id: 1,
            parent_order_id: 55,
            slice_id: 77,
            timestamp: 100,
            shm_ref,
        };
        assert!(publisher.publish(&event.encode()));

        let mut service = SorClusterService::new(in_topic.subscriber(), out_topic.publisher());
        let count = service.poll(&mut region);
        assert_eq!(count, 1);

        let mut out_sub = out_topic.subscriber();
        let raw = out_sub.poll().expect("expected route ref event");
        let route_evt = SorRouteRefEvent::decode(&raw).unwrap();
        assert_eq!(route_evt.slice_id, 77);
    }
}

