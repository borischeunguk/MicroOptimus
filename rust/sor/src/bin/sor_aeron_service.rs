#[cfg(feature = "aeron-integration")]
mod enabled {
    use std::env;

    use common::cluster::{AeronClusterPublisher, AeronClusterSubscriber};
    use common::shm::SharedRegion;
    use common::types::VenueId;
    use sor::cluster_service::SorClusterService;
    use sor::router::SmartOrderRouter;
    use sor::venue::VenueConfig;

    fn parse_arg<T: std::str::FromStr>(key: &str) -> T {
        let value = env::var(key).unwrap_or_else(|_| panic!("missing env var: {key}"));
        value
            .parse::<T>()
            .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
    }

    fn parse_optional_arg<T: std::str::FromStr>(key: &str) -> Option<T> {
        std::env::var(key).ok().map(|value| {
            value
                .parse::<T>()
                .unwrap_or_else(|_| panic!("invalid env var {key}: {value}"))
        })
    }

    fn configure_router(router: &mut SmartOrderRouter) {
        router.configure_venue(VenueConfig::new(
            VenueId::Cme,
            90,
            true,
            1_500_000,
            130_000,
            0.95,
            0.00010,
        ));
        router.configure_venue(VenueConfig::new(
            VenueId::Nasdaq,
            85,
            true,
            1_200_000,
            170_000,
            0.92,
            0.00015,
        ));
        router.set_internal_liquidity_threshold(100);
    }

    pub fn run() {
        let slice_stream = parse_arg::<i32>("MO_ALGO_SLICE_STREAM");
        let route_stream = parse_arg::<i32>("MO_SOR_ROUTE_STREAM");
        let shm_path = env::var("MO_SHM_PATH").expect("missing env var: MO_SHM_PATH");
        let region_id = parse_arg::<u32>("MO_SHM_REGION_ID");
        let shm_capacity = parse_arg::<usize>("MO_SHM_CAPACITY");
        let target_routes = parse_optional_arg::<u64>("MO_TARGET_ROUTES");

        let slice_sub =
            AeronClusterSubscriber::new("aeron:ipc", slice_stream).expect("sor slice subscriber");
        let route_pub =
            AeronClusterPublisher::new("aeron:ipc", route_stream).expect("sor route publisher");

        let mut router = SmartOrderRouter::new();
        router.initialize();
        configure_router(&mut router);

        let mut service = SorClusterService::new_with_router(slice_sub, route_pub, router);
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
    eprintln!("sor_aeron_service requires --features aeron-integration");
    std::process::exit(1);
}

