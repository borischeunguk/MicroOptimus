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

/// Placeholder Aeron wrapper configuration for single-node MVP wiring.
#[derive(Debug, Clone)]
pub struct AeronWrapperConfig {
    pub channel: String,
    pub stream_id: i32,
}

impl Default for AeronWrapperConfig {
    fn default() -> Self {
        Self {
            channel: "aeron:udp?endpoint=127.0.0.1:9010".to_string(),
            stream_id: 10,
        }
    }
}

/// Wrapper handle for future real Aeron integration.
///
/// MVP currently uses in-memory topics while keeping a stable surface.
pub struct AeronClusterWrapper {
    pub config: AeronWrapperConfig,
}

impl AeronClusterWrapper {
    pub fn new(config: AeronWrapperConfig) -> Self {
        Self { config }
    }
}

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

