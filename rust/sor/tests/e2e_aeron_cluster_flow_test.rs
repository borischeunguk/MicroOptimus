/// Integration test: algo → sor flow over real Aeron IPC.
///
/// Requires a live Aeron media driver. Run with:
///   cargo test -p sor --features aeron-integration
///
/// The embedded media driver is started automatically via rusteron-media-driver.
#[cfg(feature = "aeron-integration")]
mod aeron_integration {
    use algo::cluster_service::AlgoClusterService;
    use common::cluster::{
        AeronClusterPublisher, AeronClusterSubscriber, ClusterPublisher, ClusterSubscriber,
        STREAM_ALGO_SLICE, STREAM_PARENT_CMD, STREAM_SOR_ROUTE,
    };
    use common::sbe::{FixedCodec, ParentOrderCommand, SorRouteRefEvent};
    use common::shm::SharedRegion;
    use sor::cluster_service::SorClusterService;
    use std::sync::atomic::Ordering;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn parent_to_route_flow_over_real_aeron_ipc() {
        let (stop_flag, driver_handle) = common::cluster::start_embedded_driver();

        // --- Publishers and subscribers wired over real Aeron IPC ---
        let mut command_publisher =
            AeronClusterPublisher::new("aeron:ipc", STREAM_PARENT_CMD)
                .expect("command publisher");
        let command_subscriber =
            AeronClusterSubscriber::new("aeron:ipc", STREAM_PARENT_CMD)
                .expect("command subscriber");

        let slice_publisher =
            AeronClusterPublisher::new("aeron:ipc", STREAM_ALGO_SLICE)
                .expect("slice publisher");
        let slice_subscriber =
            AeronClusterSubscriber::new("aeron:ipc", STREAM_ALGO_SLICE)
                .expect("slice subscriber");

        let route_publisher =
            AeronClusterPublisher::new("aeron:ipc", STREAM_SOR_ROUTE)
                .expect("route publisher");
        let mut route_subscriber =
            AeronClusterSubscriber::new("aeron:ipc", STREAM_SOR_ROUTE)
                .expect("route subscriber");

        // --- Publish a parent order command ---
        let parent = ParentOrderCommand {
            sequence_id: 1,
            parent_order_id: 9001,
            client_id: 42,
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
            max_slice_size: 2_000,
            slice_interval_ns: 0,
            ..ParentOrderCommand::default()
        };
        assert!(command_publisher.publish(&parent.encode()), "failed to publish parent command");

        // Brief poll delay to let Aeron deliver the message
        thread::sleep(Duration::from_millis(10));

        let mut region = SharedRegion::new_anon(1, 2 << 20);

        // --- Algo cluster service: consume command, produce slice refs ---
        let mut algo_service = AlgoClusterService::new(command_subscriber, slice_publisher);
        let generated = algo_service.poll(&mut region, 1_000, 15_000_000);
        assert!(generated > 0, "algo service should generate at least one slice");

        thread::sleep(Duration::from_millis(10));

        // --- SOR cluster service: consume slice refs, produce route refs ---
        let mut sor_service = SorClusterService::new(slice_subscriber, route_publisher);
        let routed = sor_service.poll(&mut region);
        assert!(routed > 0, "sor service should route at least one slice");

        thread::sleep(Duration::from_millis(10));

        // --- Verify route ref event is delivered and readable ---
        let raw = route_subscriber.poll().expect("expected at least one route ref event");
        let route = SorRouteRefEvent::decode(&raw).expect("failed to decode SorRouteRefEvent");
        assert!(route.route_id > 0, "route_id should be non-zero");

        let decision_bytes = region
            .read(&route.shm_ref)
            .expect("routing decision payload should be present in shared region");
        assert!(!decision_bytes.is_empty(), "routing decision payload should not be empty");

        // --- Shut down embedded media driver ---
        stop_flag.store(true, Ordering::SeqCst);
        let _ = driver_handle.join().expect("media driver thread panicked");
    }
}
