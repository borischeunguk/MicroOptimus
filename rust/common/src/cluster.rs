use std::sync::{Arc, Mutex};

/// Publisher interface used by services that emit sequenced events.
pub trait ClusterPublisher {
    fn publish(&mut self, payload: &[u8]) -> bool;
}

/// Subscriber interface used by services that consume sequenced events.
pub trait ClusterSubscriber {
    fn poll(&mut self) -> Option<Vec<u8>>;
}

/// Sequenced service abstraction for cluster-driven services.
pub trait SequencedService {
    fn poll(&mut self) -> u64;
}

#[derive(Default)]
struct TopicState {
    log: Vec<Vec<u8>>,
}

/// In-memory topic used as the default Aeron-wrapper stand-in for MVP tests.
#[derive(Clone, Default)]
pub struct InMemoryClusterTopic {
    inner: Arc<Mutex<TopicState>>,
}

impl InMemoryClusterTopic {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn publisher(&self) -> InMemoryClusterPublisher {
        InMemoryClusterPublisher {
            inner: Arc::clone(&self.inner),
        }
    }

    pub fn subscriber(&self) -> InMemoryClusterSubscriber {
        InMemoryClusterSubscriber {
            inner: Arc::clone(&self.inner),
            cursor: 0,
        }
    }
}

pub struct InMemoryClusterPublisher {
    inner: Arc<Mutex<TopicState>>,
}

impl ClusterPublisher for InMemoryClusterPublisher {
    fn publish(&mut self, payload: &[u8]) -> bool {
        let mut state = self.inner.lock().expect("cluster topic mutex poisoned");
        state.log.push(payload.to_vec());
        true
    }
}

pub struct InMemoryClusterSubscriber {
    inner: Arc<Mutex<TopicState>>,
    cursor: usize,
}

impl ClusterSubscriber for InMemoryClusterSubscriber {
    fn poll(&mut self) -> Option<Vec<u8>> {
        let state = self.inner.lock().expect("cluster topic mutex poisoned");
        if self.cursor >= state.log.len() {
            return None;
        }
        let msg = state.log[self.cursor].clone();
        self.cursor += 1;
        Some(msg)
    }
}

/// Configuration for an Aeron channel (publisher or subscriber).
#[derive(Debug, Clone)]
pub struct AeronChannelConfig {
    pub channel: String,
    pub stream_id: i32,
}

impl AeronChannelConfig {
    /// IPC channel (shared-memory, same host). Lowest latency.
    pub fn ipc(stream_id: i32) -> Self {
        Self {
            channel: "aeron:ipc".to_owned(),
            stream_id,
        }
    }

    /// UDP unicast channel (separate processes).
    pub fn udp(endpoint: &str, stream_id: i32) -> Self {
        Self {
            channel: format!("aeron:udp?endpoint={endpoint}"),
            stream_id,
        }
    }
}

// Stream IDs for the algo → sor pipeline:
//   STREAM_PARENT_CMD  = 9   (signal/external → algo)
//   STREAM_ALGO_SLICE  = 10  (algo → sor, AlgoSliceRefEvent)
//   STREAM_SOR_ROUTE   = 11  (sor → downstream, SorRouteRefEvent)
pub const STREAM_PARENT_CMD: i32 = 9;
pub const STREAM_ALGO_SLICE: i32 = 10;
pub const STREAM_SOR_ROUTE: i32 = 11;
pub const AERON_DIR_ENV: &str = "MO_AERON_DIR";

#[cfg(feature = "aeron")]
mod aeron_impl {
    use super::{ClusterPublisher, ClusterSubscriber, AERON_DIR_ENV};
    use rusteron_client::{Aeron, AeronContext, AeronPublication, AeronSubscription, Handlers};
    use std::collections::VecDeque;
    use std::ffi::CString;
    use std::time::Duration;

    const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

    fn connect() -> Result<(AeronContext, Aeron), rusteron_client::AeronCError> {
        let ctx = AeronContext::new()?;
        if let Ok(dir) = std::env::var(AERON_DIR_ENV) {
            let c_dir = CString::new(dir).expect("Aeron directory must not contain null bytes");
            ctx.set_dir(&c_dir)?;
        }
        // Bench loops can hold busy CPU for long windows; relax driver timeout to avoid false liveness loss.
        let _ = ctx.set_driver_timeout_ms(60_000);
        let aeron = Aeron::new(&ctx)?;
        aeron.start()?;
        Ok((ctx, aeron))
    }

    /// Real Aeron publisher. Satisfies [`ClusterPublisher`] over an `aeron:ipc` or UDP channel.
    pub struct AeronClusterPublisher {
        _ctx: AeronContext,
        _aeron: Aeron,
        publication: AeronPublication,
    }

    impl AeronClusterPublisher {
        pub fn new(channel: &str, stream_id: i32) -> Result<Self, rusteron_client::AeronCError> {
            let (ctx, aeron) = connect()?;
            let c_channel = CString::new(channel).expect("channel URI must be valid UTF-8 without nulls");
            let publication = aeron.add_publication(&c_channel, stream_id, CONNECT_TIMEOUT)?;
            Ok(Self { _ctx: ctx, _aeron: aeron, publication })
        }
    }

    impl ClusterPublisher for AeronClusterPublisher {
        fn publish(&mut self, payload: &[u8]) -> bool {
            // offer_once passes the reserved-value supplier as a closure; returning 0 means no reservation.
            self.publication.offer_once(payload, |_, _| 0) >= 0
        }
    }

    /// Real Aeron subscriber. Satisfies [`ClusterSubscriber`] over an `aeron:ipc` or UDP channel.
    ///
    /// Aeron's poll API is callback-based (push); this adapter buffers received fragments
    /// internally so that the pull-based `ClusterSubscriber::poll()` trait can be satisfied.
    pub struct AeronClusterSubscriber {
        _ctx: AeronContext,
        _aeron: Aeron,
        subscription: AeronSubscription,
        buffer: VecDeque<Vec<u8>>,
    }

    impl AeronClusterSubscriber {
        pub fn new(channel: &str, stream_id: i32) -> Result<Self, rusteron_client::AeronCError> {
            let (ctx, aeron) = connect()?;
            let c_channel = CString::new(channel).expect("channel URI must be valid UTF-8 without nulls");
            let subscription = aeron.add_subscription(
                &c_channel,
                stream_id,
                Handlers::no_available_image_handler(),
                Handlers::no_unavailable_image_handler(),
                CONNECT_TIMEOUT,
            )?;
            Ok(Self { _ctx: ctx, _aeron: aeron, subscription, buffer: VecDeque::new() })
        }
    }

    impl ClusterSubscriber for AeronClusterSubscriber {
        fn poll(&mut self) -> Option<Vec<u8>> {
            let mut collected: Vec<Vec<u8>> = Vec::new();
            // poll_once drains up to 10 fragments per call into `collected`
            let _ = self.subscription.poll_once(
                |bytes: &[u8], _header| collected.push(bytes.to_vec()),
                10,
            );
            self.buffer.extend(collected);
            self.buffer.pop_front()
        }
    }
}

#[cfg(feature = "aeron")]
pub use aeron_impl::{AeronClusterPublisher, AeronClusterSubscriber};

#[cfg(feature = "aeron")]
mod driver_impl {
    use super::AERON_DIR_ENV;
    use rusteron_media_driver::{AeronCError, AeronDriver, AeronDriverContext};
    use std::ffi::CString;
    use std::sync::{atomic::AtomicBool, Arc};
    use std::thread::{self, JoinHandle};
    use std::time::Duration;

    /// Starts an embedded Aeron media driver in a background thread.
    ///
    /// Returns `(stop_flag, join_handle)`. Set `stop_flag` to `true` and join the
    /// handle to shut down gracefully.
    ///
    /// Sleeps 200ms after spawning to let the driver bind before callers connect.
    pub fn start_embedded_driver() -> (Arc<AtomicBool>, JoinHandle<Result<(), AeronCError>>) {
        let driver_ctx = AeronDriverContext::new().expect("failed to create AeronDriverContext");
        if let Ok(dir) = std::env::var(AERON_DIR_ENV) {
            let c_dir = CString::new(dir).expect("Aeron directory must not contain null bytes");
            driver_ctx.set_dir(&c_dir).expect("set_dir");
        }
        // Delete any stale state left by a previous process, and clean up on shutdown.
        driver_ctx.set_dir_delete_on_start(true).expect("set_dir_delete_on_start");
        driver_ctx.set_dir_delete_on_shutdown(true).expect("set_dir_delete_on_shutdown");
        let (stop, handle) = AeronDriver::launch_embedded(driver_ctx, true);
        thread::sleep(Duration::from_millis(200));
        (stop, handle)
    }
}

#[cfg(feature = "aeron")]
pub use driver_impl::start_embedded_driver;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_topic_roundtrip() {
        let topic = InMemoryClusterTopic::new();
        let mut publisher = topic.publisher();
        let mut subscriber = topic.subscriber();

        assert!(publisher.publish(&[1, 2, 3]));
        assert_eq!(subscriber.poll(), Some(vec![1, 2, 3]));
        assert_eq!(subscriber.poll(), None);
    }
}

