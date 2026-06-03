use std::collections::VecDeque;

use common::sbe::MarketDataUpdate;
use common::shm::{MarketDataRegion, MD_MAX_SYMBOLS};

/// Capacity of each per-symbol ring buffer.
pub const RING_BUFFER_CAPACITY: usize = 64;

/// Maintains per-symbol ring buffers of recent `MarketDataUpdate` ticks and
/// writes the latest snapshot into a dedicated `MarketDataRegion`.
///
/// # Hot-path allocation
/// The `VecDeque` capacities are pre-allocated at construction; `push_back` /
/// `pop_front` on a non-growing deque do not allocate on the heap.
pub struct MarketDataService {
    ring_buffers: Vec<VecDeque<MarketDataUpdate>>,
    region: MarketDataRegion,
    ticks_processed: u64,
}

impl MarketDataService {
    pub fn new(region: MarketDataRegion) -> Self {
        let ring_buffers = (0..MD_MAX_SYMBOLS)
            .map(|_| VecDeque::with_capacity(RING_BUFFER_CAPACITY))
            .collect();
        Self { ring_buffers, region, ticks_processed: 0 }
    }

    /// Ingest a single tick: push into the ring buffer, evict oldest if full,
    /// then overwrite the symbol's slot in the shared region.
    ///
    /// Returns `true` if the tick was accepted (valid symbol index).
    pub fn on_tick(&mut self, tick: MarketDataUpdate) -> bool {
        let sym = tick.symbol_index as usize;
        if sym >= MD_MAX_SYMBOLS {
            return false;
        }
        let buf = &mut self.ring_buffers[sym];
        if buf.len() == RING_BUFFER_CAPACITY {
            buf.pop_front(); // drop oldest
        }
        buf.push_back(tick);
        // Always write the latest tick as the snapshot for low-latency readers.
        self.region.write_snapshot(sym, &tick);
        self.ticks_processed += 1;
        true
    }

    /// Latest tick for `symbol`, if any has been received.
    pub fn latest(&self, symbol: usize) -> Option<&MarketDataUpdate> {
        self.ring_buffers.get(symbol)?.back()
    }

    pub fn ticks_processed(&self) -> u64 {
        self.ticks_processed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_region() -> MarketDataRegion {
        // Use an anonymous (non-file) region for unit tests.
        let path = std::env::temp_dir()
            .join(format!("mo_md_svc_test_{}.dat", std::process::id()))
            .to_string_lossy()
            .into_owned();
        MarketDataRegion::create(&path, MD_MAX_SYMBOLS)
    }

    #[test]
    fn ring_buffer_capacity_respected() {
        let region = make_region();
        let mut svc = MarketDataService::new(region);
        for i in 0..(RING_BUFFER_CAPACITY + 10) as u64 {
            let tick = MarketDataUpdate { symbol_index: 0, timestamp: i, ..Default::default() };
            assert!(svc.on_tick(tick));
        }
        assert_eq!(svc.ring_buffers[0].len(), RING_BUFFER_CAPACITY);
        // Latest should be the last inserted
        assert_eq!(
            svc.latest(0).unwrap().timestamp,
            (RING_BUFFER_CAPACITY + 10 - 1) as u64
        );
    }

    #[test]
    fn snapshot_written_to_region() {
        let path = std::env::temp_dir()
            .join(format!("mo_md_svc_snap_{}.dat", std::process::id()))
            .to_string_lossy()
            .into_owned();
        // Use a separate reader to verify the seqlock write.
        let region_w = MarketDataRegion::create(&path, MD_MAX_SYMBOLS);
        let region_r = MarketDataRegion::open(&path, MD_MAX_SYMBOLS);
        let mut svc = MarketDataService::new(region_w);
        let tick = MarketDataUpdate {
            symbol_index: 0,
            bid_price: 1_499,
            ask_price: 1_501,
            last_price: 1_500,
            bid_size: 10_000,
            ask_size: 10_000,
            timestamp: 100,
            ..Default::default()
        };
        svc.on_tick(tick);
        let snap = region_r.try_read_snapshot(0).expect("snapshot should exist");
        assert_eq!(snap.bid_price, 1_499);
        assert_eq!(snap.ask_price, 1_501);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn invalid_symbol_rejected() {
        let region = make_region();
        let mut svc = MarketDataService::new(region);
        let tick =
            MarketDataUpdate { symbol_index: MD_MAX_SYMBOLS as u32 + 1, ..Default::default() };
        assert!(!svc.on_tick(tick));
    }
}

