#[cfg(feature = "chronicle-integration")]
mod enabled {
    use std::env;

    use common::chronicle_cluster::{
        ChronicleClusterConfig, ChronicleClusterSubscriber, CHRONICLE_DIR_ENV,
    };
    use common::cluster::ClusterSubscriber;
    use common::sbe::{FixedCodec, MarketDataUpdate};
    use common::shm::{MarketDataRegion, MD_MAX_SYMBOLS};
    use marketdata::MarketDataService;

    const DEFAULT_QUEUE_CAPACITY: usize = 64 << 20;

    fn parse_arg<T: std::str::FromStr>(key: &str) -> T {
        let value = env::var(key).unwrap_or_else(|_| panic!("missing env var: {key}"));
        value
            .parse::<T>()
            .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
    }

    fn parse_bool_flag(key: &str) -> bool {
        env::var(key)
            .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
            .unwrap_or(false)
    }

    #[inline]
    fn xorshift64(mut x: u64) -> u64 {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        x
    }

    pub fn run() {
        let md_stream = parse_arg::<i32>("MO_MARKET_DATA_STREAM");
        let md_shm_path = env::var("MO_MD_SHM_PATH").expect("missing env var: MO_MD_SHM_PATH");
        let num_symbols = env::var("MO_MD_SHM_CAPACITY")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(MD_MAX_SYMBOLS);
        let target_ticks: Option<u64> = env::var("MO_TARGET_TICKS").ok().and_then(|v| v.parse().ok());
        let synth_enabled = parse_bool_flag("MO_MD_SYNTHETIC_ENABLE");
        let synth_symbol = env::var("MO_MD_SYNTHETIC_SYMBOL")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(0);
        let synth_base = env::var("MO_MD_SYNTHETIC_BASE_PRICE")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(1_500)
            .max(2);
        let queue_capacity = env::var("MO_CHRONICLE_QUEUE_CAPACITY")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(DEFAULT_QUEUE_CAPACITY);

        assert!(
            synth_symbol < num_symbols,
            "MO_MD_SYNTHETIC_SYMBOL {} out of range [0, {})",
            synth_symbol,
            num_symbols
        );

        let region = MarketDataRegion::open(&md_shm_path, num_symbols);
        let mut service = MarketDataService::new(region);
        let mut synth_state = 0x9E37_79B9_7F4A_7C15_u64 ^ md_stream as u64;
        let mut synth_seq = 0_u64;
        let mut synth_mid = synth_base as i64;

        let chronicle_dir = env::var(CHRONICLE_DIR_ENV)
            .unwrap_or_else(|_| panic!("missing env var: {CHRONICLE_DIR_ENV}"));
        let md_cfg = ChronicleClusterConfig::new(chronicle_dir, md_stream, queue_capacity);
        let mut sub = ChronicleClusterSubscriber::open(&md_cfg)
            .expect("failed to create chronicle market-data subscriber");

        loop {
            while let Some(bytes) = sub.poll() {
                if let Some(tick) = MarketDataUpdate::decode(&bytes) {
                    service.on_tick(tick);
                }
            }

            if synth_enabled {
                synth_state = xorshift64(synth_state.wrapping_add(0xA076_1D64_78BD_642F));
                let step = match synth_state % 3 {
                    0 => -1_i64,
                    1 => 0_i64,
                    _ => 1_i64,
                };
                synth_mid = (synth_mid + step).max(2);
                let spread = (1 + ((synth_state >> 8) % 4)) as i64;
                let bid = (synth_mid - (spread / 2)).max(1) as u64;
                let ask = (bid as i64 + spread).max(bid as i64 + 1) as u64;
                let bid_size = 10_000 + ((synth_state >> 16) % 90_001);
                let ask_size = 10_000 + ((synth_state >> 24) % 90_001);

                let synthetic_tick = MarketDataUpdate {
                    symbol_index: synth_symbol as u32,
                    bid_price: bid,
                    ask_price: ask,
                    last_price: ((bid + ask) / 2).max(1),
                    bid_size,
                    ask_size,
                    timestamp: synth_seq,
                    ..Default::default()
                };

                assert!(
                    synthetic_tick.bid_price < synthetic_tick.ask_price,
                    "invalid synthetic snapshot: bid {} >= ask {}",
                    synthetic_tick.bid_price,
                    synthetic_tick.ask_price
                );
                assert!(
                    synthetic_tick.bid_size > 0 && synthetic_tick.ask_size > 0,
                    "invalid synthetic size: bid_size={} ask_size={}",
                    synthetic_tick.bid_size,
                    synthetic_tick.ask_size
                );

                service.on_tick(synthetic_tick);
                synth_seq = synth_seq.wrapping_add(1);
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

#[cfg(feature = "chronicle-integration")]
fn main() {
    enabled::run();
}

#[cfg(not(feature = "chronicle-integration"))]
fn main() {
    eprintln!("marketdata_chronicle_service requires --features chronicle-integration");
    std::process::exit(1);
}

