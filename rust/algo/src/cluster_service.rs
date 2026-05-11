use common::cluster::{ClusterPublisher, ClusterSubscriber};
use common::messages::SliceMsg;
use common::sbe::{AlgoSliceRefEvent, FixedCodec, ParentOrderCommand};
use common::shm::SharedRegion;
use common::types::AlgorithmType;

use crate::algo_order::VwapParams;
use crate::engine::AlgoEngine;

/// Aeron-cluster-facing adapter for the Algo engine.
pub struct AlgoClusterService<S: ClusterSubscriber, P: ClusterPublisher> {
    subscriber: S,
    publisher: P,
    engine: AlgoEngine,
    local_seq: u64,
}

impl<S: ClusterSubscriber, P: ClusterPublisher> AlgoClusterService<S, P> {
    pub fn new(subscriber: S, publisher: P) -> Self {
        Self {
            subscriber,
            publisher,
            engine: AlgoEngine::new(),
            local_seq: 1,
        }
    }

    pub fn poll(&mut self, region: &mut SharedRegion, current_time: u64, current_price: u64) -> u64 {
        while let Some(bytes) = self.subscriber.poll() {
            let Some(cmd) = ParentOrderCommand::decode(&bytes) else {
                continue;
            };

            let params = VwapParams {
                num_buckets: cmd.num_buckets,
                participation_rate: cmd.participation_rate,
                min_slice_size: cmd.min_slice_size,
                max_slice_size: cmd.max_slice_size,
                slice_interval_ns: cmd.slice_interval_ns,
            };

            let order_id = self.engine.submit_order(
                cmd.client_id,
                cmd.symbol_index,
                cmd.side(),
                cmd.total_quantity,
                cmd.limit_price,
                AlgorithmType::Vwap,
                params,
                cmd.start_time,
                cmd.end_time,
                cmd.timestamp,
            );
            let _ = self.engine.start_order(order_id, cmd.timestamp);
        }

        let slices = self.engine.process_orders(current_time, current_price);
        let mut published = 0u64;

        for (
            slice_id,
            parent_order_id,
            symbol_index,
            side,
            quantity,
            price,
            slice_number,
            timestamp,
        ) in slices
        {
            let msg = SliceMsg {
                slice_id,
                parent_order_id,
                symbol_index,
                side,
                quantity,
                price,
                slice_number,
                timestamp,
                ..SliceMsg::default()
            };
            let payload = unsafe {
                std::slice::from_raw_parts(
                    &msg as *const SliceMsg as *const u8,
                    std::mem::size_of::<SliceMsg>(),
                )
            };

            let Some(shm_ref) = region.write(2, payload) else {
                continue;
            };

            let event = AlgoSliceRefEvent {
                sequence_id: self.local_seq,
                parent_order_id,
                slice_id,
                timestamp,
                shm_ref,
            };
            self.local_seq += 1;

            if self.publisher.publish(&event.encode()) {
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

    #[test]
    fn generates_slice_ref_events() {
        let command_topic = InMemoryClusterTopic::new();
        let slice_topic = InMemoryClusterTopic::new();

        let mut cmd_publisher = command_topic.publisher();
        let cmd = ParentOrderCommand {
            sequence_id: 1,
            parent_order_id: 99,
            client_id: 7,
            symbol_index: 1,
            side: 0,
            total_quantity: 10_000,
            limit_price: 15_000_000,
            start_time: 0,
            end_time: 100_000,
            timestamp: 0,
            num_buckets: 10,
            participation_rate: 0.10,
            min_slice_size: 100,
            max_slice_size: 5_000,
            slice_interval_ns: 0,
            ..ParentOrderCommand::default()
        };
        assert!(cmd_publisher.publish(&cmd.encode()));

        let mut service = AlgoClusterService::new(command_topic.subscriber(), slice_topic.publisher());
        let mut region = SharedRegion::new_anon(1, 1 << 20);

        let emitted = service.poll(&mut region, 1_000, 15_000_000);
        assert!(emitted > 0);

        let mut sub = slice_topic.subscriber();
        let raw = sub.poll().expect("expected at least one slice ref event");
        let evt = AlgoSliceRefEvent::decode(&raw).unwrap();
        assert!(evt.slice_id > 0);
        assert_eq!(evt.shm_ref.region_id, 1);
    }
}

