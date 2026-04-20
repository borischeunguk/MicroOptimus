use common::ipc::{IpcPublisher, IpcSubscriber};
use common::messages::{SliceMsg, VwapParamsMsg};
use common::types::AlgorithmType;

use crate::algo_order::VwapParams;
use crate::engine::AlgoEngine;

/// AlgoService wraps the AlgoEngine with IPC channels.
///
/// Reads VWAP parameters from signal (IpcSubscriber), processes orders,
/// and publishes slices to SOR (IpcPublisher).
pub struct AlgoService<S: IpcSubscriber, P: IpcPublisher> {
    subscriber: S,
    publisher: P,
    engine: AlgoEngine,
    messages_received: u64,
    slices_published: u64,
}

impl<S: IpcSubscriber, P: IpcPublisher> AlgoService<S, P> {
    pub fn new(subscriber: S, publisher: P) -> Self {
        Self {
            subscriber,
            publisher,
            engine: AlgoEngine::new(),
            messages_received: 0,
            slices_published: 0,
        }
    }

    /// Submit an order to the engine (called externally or from IPC).
    #[allow(clippy::too_many_arguments)]
    pub fn submit_order(
        &mut self,
        client_id: u64,
        symbol_index: u32,
        side: common::types::Side,
        total_quantity: u64,
        limit_price: u64,
        params: VwapParams,
        start_time: u64,
        end_time: u64,
        timestamp: u64,
    ) -> u64 {
        self.engine.submit_order(
            client_id,
            symbol_index,
            side,
            total_quantity,
            limit_price,
            AlgorithmType::Vwap,
            params,
            start_time,
            end_time,
            timestamp,
        )
    }

    /// Start an order.
    pub fn start_order(&mut self, order_id: u64, timestamp: u64) -> bool {
        self.engine.start_order(order_id, timestamp)
    }

    /// Poll for incoming VWAP params from signal and process orders.
    ///
    /// Returns the number of slices published.
    pub fn poll(&mut self, current_time: u64, current_price: u64) -> u64 {
        // Read any incoming VWAP parameter updates
        while let Some(data) = self.subscriber.poll() {
            if data.len() >= std::mem::size_of::<VwapParamsMsg>() {
                // We received VWAP params - could update active orders' params here
                self.messages_received += 1;
            }
        }

        // Process active orders
        let slices = self.engine.process_orders(current_time, current_price);

        let mut published = 0u64;
        for (slice_id, parent_order_id, symbol_index, side, quantity, price, slice_number, timestamp) in slices {
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

            let bytes: &[u8] = unsafe {
                std::slice::from_raw_parts(
                    &msg as *const SliceMsg as *const u8,
                    std::mem::size_of::<SliceMsg>(),
                )
            };

            if self.publisher.publish(bytes) {
                self.slices_published += 1;
                published += 1;
            }
        }

        published
    }

    pub fn engine(&self) -> &AlgoEngine {
        &self.engine
    }

    pub fn engine_mut(&mut self) -> &mut AlgoEngine {
        &mut self.engine
    }

    pub fn messages_received(&self) -> u64 {
        self.messages_received
    }

    pub fn slices_published(&self) -> u64 {
        self.slices_published
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::ipc::MmapRingBuffer;
    use common::types::Side;

    #[test]
    fn test_algo_service_poll() {
        let sub = MmapRingBuffer::new(4096);
        let pub_rb = MmapRingBuffer::new(4096);
        let mut service = AlgoService::new(sub, pub_rb);

        let params = VwapParams {
            num_buckets: 10,
            participation_rate: 0.10,
            min_slice_size: 100,
            max_slice_size: 10_000,
            slice_interval_ns: 0,
        };

        let id = service.submit_order(1, 0, Side::Buy, 10_000, 15_000_000, params, 0, 100_000, 0);
        service.start_order(id, 0);

        let published = service.poll(1000, 15_000_000);
        assert!(published > 0, "should publish at least one slice");
        assert!(service.slices_published() > 0);
    }
}
