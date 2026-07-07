#[cfg(feature = "chronicle-integration")]
mod enabled {
    use std::env;

    use algo::cluster_service::AlgoClusterService;
    use common::chronicle_cluster::{
        ChronicleClusterConfig, ChronicleClusterPublisher, ChronicleClusterSubscriber,
        CHRONICLE_DIR_ENV,
    };
    use common::shm::SharedRegion;

    const DEFAULT_QUEUE_CAPACITY: usize = 64 << 20;

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
        let cmd_stream = parse_arg::<i32>("MO_PARENT_CMD_STREAM");
        let slice_stream = parse_arg::<i32>("MO_ALGO_SLICE_STREAM");
        let shm_path = env::var("MO_SHM_PATH").expect("missing env var: MO_SHM_PATH");
        let region_id = parse_arg::<u32>("MO_SHM_REGION_ID");
        let shm_capacity = parse_arg::<usize>("MO_SHM_CAPACITY");
        let target_slices = parse_optional_arg::<u64>("MO_TARGET_SLICES");
        let current_time = parse_arg::<u64>("MO_CURRENT_TIME");
        let current_price = parse_arg::<u64>("MO_CURRENT_PRICE");
        let queue_capacity = env::var("MO_CHRONICLE_QUEUE_CAPACITY")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(DEFAULT_QUEUE_CAPACITY);

        let chronicle_dir = env::var(CHRONICLE_DIR_ENV)
            .unwrap_or_else(|_| panic!("missing env var: {CHRONICLE_DIR_ENV}"));

        let cmd_cfg = ChronicleClusterConfig::new(&chronicle_dir, cmd_stream, queue_capacity);
        let slice_cfg = ChronicleClusterConfig::new(&chronicle_dir, slice_stream, queue_capacity);

        // Strict fail-fast: queues must already exist and pass header validation.
        let cmd_sub =
            ChronicleClusterSubscriber::open(&cmd_cfg).expect("algo chronicle cmd subscriber");
        let slice_pub =
            ChronicleClusterPublisher::open(&slice_cfg).expect("algo chronicle slice publisher");

        let mut service = AlgoClusterService::new(cmd_sub, slice_pub);
        let mut region = SharedRegion::open_existing(shm_path, region_id, shm_capacity);

        let mut emitted = 0u64;
        loop {
            emitted += service.poll(&mut region, current_time, current_price);
            if let Some(target) = target_slices {
                if emitted >= target {
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
    eprintln!("algo_chronicle_service requires --features chronicle-integration");
    std::process::exit(1);
}

