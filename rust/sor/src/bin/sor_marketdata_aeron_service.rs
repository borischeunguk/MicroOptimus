/// sor_marketdata_aeron_service
///
/// Market-data-aware SOR: subscribes to the algo-slice Aeron stream, reads a
/// fresh `MarketDataRegion` snapshot on each routing decision, and publishes
/// route events.
///
/// Environment variables:
///   MO_AERON_DIR          — Aeron media driver directory
///   MO_ALGO_SLICE_STREAM  — stream id for incoming algo slices
///   MO_SOR_ROUTE_STREAM   — stream id for outgoing route events
///   MO_SHM_PATH           — path of the algo/sor shared mmap file
///   MO_SHM_REGION_ID      — region id inside the shared mmap file
///   MO_SHM_CAPACITY       — byte capacity of the algo/sor shared mmap
///   MO_MD_SHM_PATH        — path of the market-data mmap file (created by coordinator)
///   MO_MD_SHM_CAPACITY    — number of symbols in the MD region (default 16)
///   MO_TARGET_ROUTES      — (optional) stop after N routed slices

#[cfg(feature = "aeron-integration")]
mod enabled {
    use std::env;

    use common::cluster::{AeronClusterPublisher, AeronClusterSubscriber};
    use common::shm::{MarketDataRegion, SharedRegion, MD_MAX_SYMBOLS};
    use sor::cluster_service_md::SorMdClusterService;
    use sor::router_marketdata::MarketDataRouter;

    fn parse_arg<T: std::str::FromStr>(key: &str) -> T {
        let value = env::var(key).unwrap_or_else(|_| panic!("missing env var: {key}"));
        value
            .parse::<T>()
            .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
    }

    fn parse_optional_arg<T: std::str::FromStr>(key: &str) -> Option<T> {
        env::var(key).ok().map(|value| {
            value
                .parse::<T>()
                .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
        })
    }

    pub fn run() {
        let slice_stream = parse_arg::<i32>("MO_ALGO_SLICE_STREAM");
        let route_stream = parse_arg::<i32>("MO_SOR_ROUTE_STREAM");
        let shm_path = env::var("MO_SHM_PATH").expect("missing env var: MO_SHM_PATH");
        let region_id = parse_arg::<u32>("MO_SHM_REGION_ID");
        let shm_capacity = parse_arg::<usize>("MO_SHM_CAPACITY");
        let md_shm_path = env::var("MO_MD_SHM_PATH").expect("missing env var: MO_MD_SHM_PATH");
        let md_num_symbols = env::var("MO_MD_SHM_CAPACITY")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(MD_MAX_SYMBOLS);
        let target_routes = parse_optional_arg::<u64>("MO_TARGET_ROUTES");

        let slice_sub = AeronClusterSubscriber::new("aeron:ipc", slice_stream)
            .expect("sor-md slice subscriber");
        let route_pub = AeronClusterPublisher::new("aeron:ipc", route_stream)
            .expect("sor-md route publisher");

        // Open the market-data region created by the coordinator.
        let md_region = MarketDataRegion::open(&md_shm_path, md_num_symbols);
        let router = MarketDataRouter::new(md_region);

        let mut service = SorMdClusterService::new(slice_sub, route_pub, router);
        let mut region = SharedRegion::open_existing(shm_path, region_id, shm_capacity);

        let mut routed = 0u64;
        loop {
            routed += service.poll(&mut region);
            if let Some(target) = target_routes {
                if routed >= target {
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
    eprintln!("sor_marketdata_aeron_service requires --features aeron-integration");
    std::process::exit(1);
}

