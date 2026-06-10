/// marketdata_aeron_service
///
/// Subscribes to Aeron `STREAM_MARKET_DATA` (IPC channel), ingests ticks into
/// per-symbol ring buffers, and writes the latest snapshot into a dedicated
/// `MarketDataRegion` (separate mmap file).
///
/// Environment variables:
///   MO_AERON_DIR           — Aeron media driver directory
///   MO_MARKET_DATA_STREAM  — Aeron stream id for market-data ticks
///   MO_MD_SHM_PATH         — path of the pre-created market-data mmap file
///   MO_MD_SHM_CAPACITY     — expected capacity (num_symbols, default 16)
///   MO_TARGET_TICKS        — (optional) stop after N ticks; omit for infinite loop

#[cfg(feature = "aeron-integration")]
mod enabled {
    use std::env;

    use common::cluster::{AeronClusterSubscriber, ClusterSubscriber};
    use common::sbe::{FixedCodec, MarketDataUpdate};
    use common::shm::{MarketDataRegion, MD_MAX_SYMBOLS};
    use marketdata::MarketDataService;

    fn parse_arg<T: std::str::FromStr>(key: &str) -> T {
        let value = env::var(key).unwrap_or_else(|_| panic!("missing env var: {key}"));
        value
            .parse::<T>()
            .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
    }

    pub fn run() {
        let md_stream = parse_arg::<i32>("MO_MARKET_DATA_STREAM");
        let md_shm_path = env::var("MO_MD_SHM_PATH").expect("missing env var: MO_MD_SHM_PATH");
        let num_symbols = env::var("MO_MD_SHM_CAPACITY")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(MD_MAX_SYMBOLS);
        let target_ticks: Option<u64> =
            env::var("MO_TARGET_TICKS").ok().and_then(|v| v.parse().ok());

        let region = MarketDataRegion::open(&md_shm_path, num_symbols);
        let mut service = MarketDataService::new(region);

        let mut sub = AeronClusterSubscriber::new("aeron:ipc", md_stream)
            .expect("failed to create market-data subscriber");

        loop {
            while let Some(bytes) = sub.poll() {
                if let Some(tick) = MarketDataUpdate::decode(&bytes) {
                    service.on_tick(tick);
                }
            }
            if let Some(target) = target_ticks {
                if service.ticks_processed() >= target {
                    break;
                }
            }
            std::hint::spin_loop();
        }
    }
}

#[cfg(feature = "aeron-integration")]
fn main() {
    enabled::run();
}

#[cfg(not(feature = "aeron-integration"))]
fn main() {
    eprintln!("marketdata_aeron_service requires --features aeron-integration");
    std::process::exit(1);
}

