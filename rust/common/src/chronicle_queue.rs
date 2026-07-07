use std::fmt::{Display, Formatter};
use std::fs::OpenOptions;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use memmap2::MmapMut;

/// Pinned compatibility target for the Java Chronicle Queue baseline used by this repository.
pub const SUPPORTED_CHRONICLE_QUEUE_VERSION: &str = "5.27ea";
pub const SUPPORTED_WIRE_TYPE: &str = "BINARY_LIGHT";
pub const SUPPORTED_ROLL_CYCLE: &str = "DAILY";

const HEADER_SIZE: usize = 4096;
const DATA_OFFSET: usize = HEADER_SIZE;
const ALIGNMENT: usize = 8;
const FRAME_HEADER_SIZE: usize = 8;

const MAGIC_OFFSET: usize = 0;
const VERSION_OFFSET: usize = 16;
const WIRE_OFFSET: usize = 48;
const ROLL_OFFSET: usize = 80;
const CAPACITY_OFFSET: usize = 128;
const WRITE_POS_OFFSET: usize = 192;
const READ_POS_OFFSET: usize = 256;

const MAGIC: &[u8; 16] = b"MO_CHRONICLE_V1X";
const VERSION_SLOTS: usize = 32;
const WIRE_SLOTS: usize = 32;
const ROLL_SLOTS: usize = 32;

#[derive(Debug)]
pub enum ChronicleQueueError {
    Io(std::io::Error),
    InvalidHeader(&'static str),
    UnsupportedVariant {
        field: &'static str,
        expected: &'static str,
        actual: String,
    },
}

impl Display for ChronicleQueueError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(err) => write!(f, "io error: {err}"),
            Self::InvalidHeader(msg) => write!(f, "invalid queue header: {msg}"),
            Self::UnsupportedVariant {
                field,
                expected,
                actual,
            } => write!(
                f,
                "unsupported chronicle {field}: expected '{expected}', got '{actual}'"
            ),
        }
    }
}

impl std::error::Error for ChronicleQueueError {}

impl From<std::io::Error> for ChronicleQueueError {
    fn from(value: std::io::Error) -> Self {
        Self::Io(value)
    }
}

#[inline]
fn align_up(v: usize, align: usize) -> usize {
    (v + align - 1) & !(align - 1)
}

fn write_fixed_ascii(dst: &mut [u8], value: &str) {
    dst.fill(0);
    let bytes = value.as_bytes();
    let len = bytes.len().min(dst.len());
    dst[..len].copy_from_slice(&bytes[..len]);
}

fn read_fixed_ascii(src: &[u8]) -> String {
    let end = src.iter().position(|b| *b == 0).unwrap_or(src.len());
    String::from_utf8_lossy(&src[..end]).to_string()
}

/// Minimal queue configuration with strict format guards.
#[derive(Debug, Clone)]
pub struct ChronicleQueueConfig {
    pub path: PathBuf,
    pub capacity: usize,
    pub version: &'static str,
    pub wire_type: &'static str,
    pub roll_cycle: &'static str,
}

impl ChronicleQueueConfig {
    pub fn new(path: impl Into<PathBuf>, capacity: usize) -> Self {
        Self {
            path: path.into(),
            capacity,
            version: SUPPORTED_CHRONICLE_QUEUE_VERSION,
            wire_type: SUPPORTED_WIRE_TYPE,
            roll_cycle: SUPPORTED_ROLL_CYCLE,
        }
    }
}

/// Chronicle-style mmap queue with strict, fail-fast header compatibility checks.
#[derive(Debug)]
pub struct ChronicleQueue {
    mmap: MmapMut,
    capacity: usize,
}

impl ChronicleQueue {
    pub fn create(config: &ChronicleQueueConfig) -> Result<Self, ChronicleQueueError> {
        if config.capacity < 1024 {
            return Err(ChronicleQueueError::InvalidHeader(
                "capacity must be >= 1024 bytes",
            ));
        }
        let total = HEADER_SIZE + config.capacity;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&config.path)?;
        file.set_len(total as u64)?;
        let mmap = unsafe { MmapMut::map_mut(&file)? };
        let mut q = Self {
            mmap,
            capacity: config.capacity,
        };
        q.write_header(config);
        Ok(q)
    }

    pub fn open(config: &ChronicleQueueConfig) -> Result<Self, ChronicleQueueError> {
        let total = HEADER_SIZE + config.capacity;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&config.path)?;
        if file.metadata()?.len() < total as u64 {
            return Err(ChronicleQueueError::InvalidHeader("queue file too small"));
        }
        let mmap = unsafe { MmapMut::map_mut(&file)? };
        let q = Self {
            mmap,
            capacity: config.capacity,
        };
        q.validate_header(config)?;
        Ok(q)
    }

    pub fn publish(&mut self, payload: &[u8]) -> bool {
        let msg_len = payload.len();
        let frame_len = align_up(FRAME_HEADER_SIZE + msg_len, ALIGNMENT);

        let write = self.write_pos().load(Ordering::Relaxed) as usize;
        let read = self.read_pos().load(Ordering::Acquire) as usize;
        let used = write.wrapping_sub(read);
        if used + frame_len > self.capacity {
            return false;
        }

        let offset = write % self.capacity;
        if offset + frame_len > self.capacity {
            let remaining = self.capacity - offset;
            if used + remaining + frame_len > self.capacity {
                return false;
            }
            let ptr = self.data_mut_ptr();
            unsafe {
                std::ptr::write(ptr.add(offset) as *mut u32, 0);
            }
            self.write_pos()
                .store((write + remaining) as u64, Ordering::Release);
            return self.publish(payload);
        }

        let ptr = self.data_mut_ptr();
        unsafe {
            std::ptr::write(ptr.add(offset) as *mut u32, msg_len as u32);
            std::ptr::copy_nonoverlapping(
                payload.as_ptr(),
                ptr.add(offset + FRAME_HEADER_SIZE),
                msg_len,
            );
        }

        self.write_pos()
            .store((write + frame_len) as u64, Ordering::Release);
        true
    }

    pub fn poll(&mut self) -> Option<Vec<u8>> {
        let read = self.read_pos().load(Ordering::Relaxed) as usize;
        let write = self.write_pos().load(Ordering::Acquire) as usize;
        if read == write {
            return None;
        }

        let offset = read % self.capacity;
        let ptr = self.data_ptr();
        let msg_len = unsafe { std::ptr::read(ptr.add(offset) as *const u32) } as usize;

        if msg_len == 0 {
            let remaining = self.capacity - offset;
            self.read_pos()
                .store((read + remaining) as u64, Ordering::Release);
            return self.poll();
        }

        let frame_len = align_up(FRAME_HEADER_SIZE + msg_len, ALIGNMENT);
        let mut out = vec![0u8; msg_len];
        unsafe {
            std::ptr::copy_nonoverlapping(
                ptr.add(offset + FRAME_HEADER_SIZE),
                out.as_mut_ptr(),
                msg_len,
            );
        }
        self.read_pos()
            .store((read + frame_len) as u64, Ordering::Release);
        Some(out)
    }

    fn write_header(&mut self, config: &ChronicleQueueConfig) {
        self.mmap[MAGIC_OFFSET..MAGIC_OFFSET + MAGIC.len()].copy_from_slice(MAGIC);
        write_fixed_ascii(
            &mut self.mmap[VERSION_OFFSET..VERSION_OFFSET + VERSION_SLOTS],
            config.version,
        );
        write_fixed_ascii(
            &mut self.mmap[WIRE_OFFSET..WIRE_OFFSET + WIRE_SLOTS],
            config.wire_type,
        );
        write_fixed_ascii(
            &mut self.mmap[ROLL_OFFSET..ROLL_OFFSET + ROLL_SLOTS],
            config.roll_cycle,
        );
        self.mmap[CAPACITY_OFFSET..CAPACITY_OFFSET + 8]
            .copy_from_slice(&(self.capacity as u64).to_ne_bytes());
        self.write_pos().store(0, Ordering::Release);
        self.read_pos().store(0, Ordering::Release);
        let _ = self.mmap.flush();
    }

    fn validate_header(&self, config: &ChronicleQueueConfig) -> Result<(), ChronicleQueueError> {
        let magic = &self.mmap[MAGIC_OFFSET..MAGIC_OFFSET + MAGIC.len()];
        if magic != MAGIC {
            return Err(ChronicleQueueError::InvalidHeader("magic mismatch"));
        }

        let version = read_fixed_ascii(&self.mmap[VERSION_OFFSET..VERSION_OFFSET + VERSION_SLOTS]);
        if version != config.version {
            return Err(ChronicleQueueError::UnsupportedVariant {
                field: "version",
                expected: config.version,
                actual: version,
            });
        }

        let wire = read_fixed_ascii(&self.mmap[WIRE_OFFSET..WIRE_OFFSET + WIRE_SLOTS]);
        if wire != config.wire_type {
            return Err(ChronicleQueueError::UnsupportedVariant {
                field: "wire_type",
                expected: config.wire_type,
                actual: wire,
            });
        }

        let roll = read_fixed_ascii(&self.mmap[ROLL_OFFSET..ROLL_OFFSET + ROLL_SLOTS]);
        if roll != config.roll_cycle {
            return Err(ChronicleQueueError::UnsupportedVariant {
                field: "roll_cycle",
                expected: config.roll_cycle,
                actual: roll,
            });
        }

        let cap = u64::from_ne_bytes(
            self.mmap[CAPACITY_OFFSET..CAPACITY_OFFSET + 8]
                .try_into()
                .expect("capacity bytes"),
        ) as usize;
        if cap != config.capacity {
            return Err(ChronicleQueueError::InvalidHeader("capacity mismatch"));
        }
        Ok(())
    }

    fn write_pos(&self) -> &AtomicU64 {
        unsafe { &*(self.mmap.as_ptr().add(WRITE_POS_OFFSET) as *const AtomicU64) }
    }

    fn read_pos(&self) -> &AtomicU64 {
        unsafe { &*(self.mmap.as_ptr().add(READ_POS_OFFSET) as *const AtomicU64) }
    }

    fn data_ptr(&self) -> *const u8 {
        unsafe { self.mmap.as_ptr().add(DATA_OFFSET) }
    }

    fn data_mut_ptr(&mut self) -> *mut u8 {
        unsafe { self.mmap.as_mut_ptr().add(DATA_OFFSET) }
    }

    #[cfg(test)]
    fn overwrite_header_field_for_test(
        path: &std::path::Path,
        offset: usize,
        len: usize,
        value: &str,
    ) -> Result<(), ChronicleQueueError> {
        let file = OpenOptions::new().read(true).write(true).open(path)?;
        let mut mmap = unsafe { MmapMut::map_mut(&file)? };
        write_fixed_ascii(&mut mmap[offset..offset + len], value);
        mmap.flush()?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "mo_chronicle_queue_{name}_{}_{}.cq4",
            std::process::id(),
            std::thread::current().name().unwrap_or("t")
        ))
    }

    #[test]
    fn queue_roundtrip() {
        let path = test_path("roundtrip");
        let cfg = ChronicleQueueConfig::new(&path, 64 << 10);
        let mut pub_q = ChronicleQueue::create(&cfg).expect("create queue");
        let mut sub_q = ChronicleQueue::open(&cfg).expect("open queue");

        assert!(pub_q.publish(b"hello chronicle"));
        let msg = sub_q.poll().expect("message");
        assert_eq!(msg, b"hello chronicle");

        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_unknown_version() {
        let path = test_path("bad_version");
        let cfg = ChronicleQueueConfig::new(&path, 64 << 10);
        let q = ChronicleQueue::create(&cfg).expect("create queue");
        drop(q);
        ChronicleQueue::overwrite_header_field_for_test(&path, VERSION_OFFSET, VERSION_SLOTS, "9.99")
            .expect("overwrite version");

        let err = ChronicleQueue::open(&cfg).expect_err("must reject unsupported version");
        assert!(matches!(
            err,
            ChronicleQueueError::UnsupportedVariant { field: "version", .. }
        ));

        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn rejects_unknown_wire() {
        let path = test_path("bad_wire");
        let cfg = ChronicleQueueConfig::new(&path, 64 << 10);
        let q = ChronicleQueue::create(&cfg).expect("create queue");
        drop(q);
        ChronicleQueue::overwrite_header_field_for_test(&path, WIRE_OFFSET, WIRE_SLOTS, "TEXT")
            .expect("overwrite wire");

        let err = ChronicleQueue::open(&cfg).expect_err("must reject unsupported wire");
        assert!(matches!(
            err,
            ChronicleQueueError::UnsupportedVariant {
                field: "wire_type",
                ..
            }
        ));

        let _ = std::fs::remove_file(path);
    }
}
