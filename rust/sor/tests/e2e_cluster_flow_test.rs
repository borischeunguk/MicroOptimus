use algo::cluster_service::AlgoClusterService;
use common::cluster::{ClusterPublisher, ClusterSubscriber, InMemoryClusterTopic};
use common::sbe::{FixedCodec, ParentOrderCommand, SorRouteRefEvent};
use common::shm::SharedRegion;
use sor::cluster_service::SorClusterService;

#[test]
fn parent_to_route_flow_over_cluster_and_shared_region() {
    let parent_command_topic = InMemoryClusterTopic::new();
    let slice_ref_topic = InMemoryClusterTopic::new();
    let route_ref_topic = InMemoryClusterTopic::new();

    let mut command_publisher = parent_command_topic.publisher();

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
    assert!(command_publisher.publish(&parent.encode()));

    let mut region = SharedRegion::new_anon(1, 2 << 20);

    let mut algo_service = AlgoClusterService::new(
        parent_command_topic.subscriber(),
        slice_ref_topic.publisher(),
    );
    let generated = algo_service.poll(&mut region, 1_000, 15_000_000);
    assert!(generated > 0);

    let mut sor_service = SorClusterService::new(slice_ref_topic.subscriber(), route_ref_topic.publisher());
    let routed = sor_service.poll(&mut region);
    assert!(routed > 0);

    let mut route_sub = route_ref_topic.subscriber();
    let raw = route_sub.poll().expect("expected route event");
    let route = SorRouteRefEvent::decode(&raw).unwrap();
    assert!(route.route_id > 0);

    let decision_bytes = region.read(&route.shm_ref).expect("decision payload should exist");
    assert!(!decision_bytes.is_empty());
}
