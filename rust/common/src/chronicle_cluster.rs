use std::path::{Path, PathBuf};

use crate::chronicle_queue::{ChronicleQueue, ChronicleQueueConfig, ChronicleQueueError};
use crate::cluster::{
    ClusterPublisher, ClusterSubscriber, STREAM_ALGO_SLICE, STREAM_MARKET_DATA, STREAM_PARENT_CMD,
    STREAM_SOR_ROUTE,
};

pub const CHRONICLE_DIR_ENV: &str = "MO_CHRONICLE_DIR";

#[derive(Debug)]
pub enum ChronicleClusterError {
    Queue(ChronicleQueueError),
    InvalidStream(i32),
}

impl From<ChronicleQueueError> for ChronicleClusterError {
    fn from(value: ChronicleQueueError) -> Self {
        Self::Queue(value)
    }
}

impl std::fmt::Display for ChronicleClusterError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Queue(err) => write!(f, "chronicle queue error: {err}"),
            Self::InvalidStream(stream_id) => {
                write!(f, "invalid stream id for chronicle mapping: {stream_id}")
            }
        }
    }
}

impl std::error::Error for ChronicleClusterError {}

#[derive(Debug, Clone)]
pub struct ChronicleClusterConfig {
    pub base_dir: PathBuf,
    pub stream_id: i32,
    pub capacity: usize,
}

impl ChronicleClusterConfig {
    pub fn new(base_dir: impl Into<PathBuf>, stream_id: i32, capacity: usize) -> Self {
        Self {
            base_dir: base_dir.into(),
            stream_id,
            capacity,
        }
    }

    pub fn queue_path(&self) -> Result<PathBuf, ChronicleClusterError> {
        queue_path_for_stream(&self.base_dir, self.stream_id)
    }

    pub fn queue_config(&self) -> Result<ChronicleQueueConfig, ChronicleClusterError> {
        Ok(ChronicleQueueConfig::new(self.queue_path()?, self.capacity))
    }
}

pub fn queue_path_for_stream(
    base_dir: impl AsRef<Path>,
    stream_id: i32,
) -> Result<PathBuf, ChronicleClusterError> {
    let file_name = match stream_id {
        STREAM_PARENT_CMD => "parent_cmd.cq4",
        STREAM_ALGO_SLICE => "algo_slice.cq4",
        STREAM_SOR_ROUTE => "sor_route.cq4",
        STREAM_MARKET_DATA => "market_data.cq4",
        _ => return Err(ChronicleClusterError::InvalidStream(stream_id)),
    };
    Ok(base_dir.as_ref().join(file_name))
}

pub struct ChronicleClusterPublisher {
    queue: ChronicleQueue,
}

impl ChronicleClusterPublisher {
    pub fn create(config: &ChronicleClusterConfig) -> Result<Self, ChronicleClusterError> {
        std::fs::create_dir_all(&config.base_dir)
            .map_err(ChronicleQueueError::Io)
            .map_err(ChronicleClusterError::Queue)?;
        let queue_cfg = config.queue_config()?;
        Ok(Self {
            queue: ChronicleQueue::create(&queue_cfg)?,
        })
    }

    pub fn open(config: &ChronicleClusterConfig) -> Result<Self, ChronicleClusterError> {
        let queue_cfg = config.queue_config()?;
        Ok(Self {
            queue: ChronicleQueue::open(&queue_cfg)?,
        })
    }
}

impl ClusterPublisher for ChronicleClusterPublisher {
    fn publish(&mut self, payload: &[u8]) -> bool {
        self.queue.publish(payload)
    }
}

pub struct ChronicleClusterSubscriber {
    queue: ChronicleQueue,
}

impl ChronicleClusterSubscriber {
    pub fn open(config: &ChronicleClusterConfig) -> Result<Self, ChronicleClusterError> {
        let queue_cfg = config.queue_config()?;
        Ok(Self {
            queue: ChronicleQueue::open(&queue_cfg)?,
        })
    }

    pub fn create(config: &ChronicleClusterConfig) -> Result<Self, ChronicleClusterError> {
        std::fs::create_dir_all(&config.base_dir)
            .map_err(ChronicleQueueError::Io)
            .map_err(ChronicleClusterError::Queue)?;
        let queue_cfg = config.queue_config()?;
        Ok(Self {
            queue: ChronicleQueue::create(&queue_cfg)?,
        })
    }
}

impl ClusterSubscriber for ChronicleClusterSubscriber {
    fn poll(&mut self) -> Option<Vec<u8>> {
        self.queue.poll()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cluster::{ClusterPublisher, ClusterSubscriber};

    fn test_dir(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mo_chronicle_cluster_{name}_{}", std::process::id()))
    }

    #[test]
    fn maps_streams_to_expected_files() {
        let dir = test_dir("map");
        let parent = queue_path_for_stream(&dir, STREAM_PARENT_CMD).expect("parent stream");
        let route = queue_path_for_stream(&dir, STREAM_SOR_ROUTE).expect("route stream");

        assert_eq!(parent.file_name().unwrap().to_string_lossy(), "parent_cmd.cq4");
        assert_eq!(route.file_name().unwrap().to_string_lossy(), "sor_route.cq4");
        assert!(queue_path_for_stream(&dir, 999).is_err());
    }

    #[test]
    fn publisher_subscriber_roundtrip() {
        let dir = test_dir("rt");
        let cfg = ChronicleClusterConfig::new(&dir, STREAM_PARENT_CMD, 64 << 10);

        let mut publisher = ChronicleClusterPublisher::create(&cfg).expect("publisher");
        let mut subscriber = ChronicleClusterSubscriber::open(&cfg).expect("subscriber");

        assert!(publisher.publish(b"ping"));
        assert_eq!(subscriber.poll(), Some(b"ping".to_vec()));

        let _ = std::fs::remove_file(cfg.queue_path().expect("queue path"));
        let _ = std::fs::remove_dir_all(dir);
    }
}
