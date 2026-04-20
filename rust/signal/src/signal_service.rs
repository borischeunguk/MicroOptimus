use common::ipc::IpcPublisher;
use common::messages::VwapParamsMsg;

use crate::vwap_params::VwapParameterGenerator;

/// Signal service that generates VWAP parameters and publishes them over IPC.
pub struct SignalService<P: IpcPublisher> {
    publisher: P,
    generator: VwapParameterGenerator,
    params_published: u64,
}

impl<P: IpcPublisher> SignalService<P> {
    pub fn new(publisher: P, generator: VwapParameterGenerator) -> Self {
        Self {
            publisher,
            generator,
            params_published: 0,
        }
    }

    /// Publish VWAP parameters for a given symbol.
    pub fn publish_params(&mut self, symbol_index: u32, timestamp: u64) -> bool {
        let msg = self.generator.to_msg(symbol_index, timestamp);
        let bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                &msg as *const VwapParamsMsg as *const u8,
                std::mem::size_of::<VwapParamsMsg>(),
            )
        };

        if self.publisher.publish(bytes) {
            self.params_published += 1;
            true
        } else {
            false
        }
    }

    pub fn params_published(&self) -> u64 {
        self.params_published
    }

    pub fn generator(&self) -> &VwapParameterGenerator {
        &self.generator
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::ipc::MmapRingBuffer;

    #[test]
    fn test_signal_service_publish() {
        let rb = MmapRingBuffer::new(4096);
        let gen = VwapParameterGenerator::new();
        let mut service = SignalService::new(rb, gen);

        assert!(service.publish_params(1, 1000));
        assert_eq!(service.params_published(), 1);

        assert!(service.publish_params(2, 2000));
        assert_eq!(service.params_published(), 2);
    }
}
