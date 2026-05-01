use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use crossbeam_utils::CachePadded;
use memmap2::MmapMut;

/// Trait for publishing messages to an IPC channel.
pub trait IpcPublisher {
    /// Publish a message as raw bytes. Returns true on success.
    fn publish(&mut self, data: &[u8]) -> bool;
}

/// Trait for subscribing to messages from an IPC channel.
pub trait IpcSubscriber {
    /// Poll for the next message. Returns a slice of bytes if available.
    /// The returned slice is only valid until the next call to `poll`.
    fn poll(&mut self) -> Option<&[u8]>;
}

/// Header layout at the start of the memory-mapped region.
///
/// ```text
/// [0..64)   write_pos (CachePadded<AtomicU64>)
/// [64..128) read_pos  (CachePadded<AtomicU64>)
/// [128..136) capacity  (u64)
/// [136..256) reserved
/// [256..)   data region
/// ```
const HEADER_SIZE: usize = 256;
const WRITE_POS_OFFSET: usize = 0;
const READ_POS_OFFSET: usize = 64;
const CAPACITY_OFFSET: usize = 128;

/// Each message is framed as: [length: u32][padding: 4 bytes][payload: length bytes], aligned to 8 bytes.
/// The 8-byte frame header ensures payload is always 8-byte aligned for zero-copy struct access.
const FRAME_HEADER_SIZE: usize = 8;
const ALIGNMENT: usize = 8;

fn align_up(val: usize, align: usize) -> usize {
    (val + align - 1) & !(align - 1)
}

/// SPSC ring buffer backed by a memory-mapped file.
///
/// Provides lock-free single-producer / single-consumer semantics with
/// Release/Acquire atomic ordering. Compatible with Aeron IPC layout semantics.
pub struct MmapRingBuffer {
    mmap: MmapMut,
    capacity: usize,
}

impl MmapRingBuffer {
    /// Create a new ring buffer backed by an anonymous mmap region (for testing / in-process IPC).
    pub fn new(capacity: usize) -> Self {
        let total = HEADER_SIZE + capacity;
        let mmap = MmapMut::map_anon(total).expect("failed to create anonymous mmap");
        let mut rb = Self { mmap, capacity };
        rb.init_header();
        rb
    }

    /// Create a new ring buffer backed by a file at `path`.
    /// Truncates and initializes the file. Use this for the producer.
    pub fn create<P: AsRef<Path>>(path: P, capacity: usize) -> Self {
        let total = HEADER_SIZE + capacity;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)
            .expect("failed to open ring buffer file");
        file.set_len(total as u64)
            .expect("failed to set file length");

        let mmap = unsafe { MmapMut::map_mut(&file).expect("failed to mmap file") };
        let mut rb = Self { mmap, capacity };
        rb.init_header();
        rb
    }

    /// Open an existing ring buffer backed by a file at `path`.
    /// Does NOT initialize the header. Use this for the consumer.
    pub fn open<P: AsRef<Path>>(path: P, capacity: usize) -> Self {
        let total = HEADER_SIZE + capacity;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .expect("failed to open ring buffer file");
        let meta = file.metadata().expect("failed to get file metadata");
        assert!(meta.len() >= total as u64, "ring buffer file too small");

        let mmap = unsafe { MmapMut::map_mut(&file).expect("failed to mmap file") };
        Self { mmap, capacity }
    }

    fn init_header(&mut self) {
        // Write capacity
        let cap_bytes = (self.capacity as u64).to_ne_bytes();
        self.mmap[CAPACITY_OFFSET..CAPACITY_OFFSET + 8].copy_from_slice(&cap_bytes);
        // Write and read positions start at 0 (mmap is zeroed for anon, but be explicit)
        self.write_pos().store(0, Ordering::Release);
        self.read_pos().store(0, Ordering::Release);
    }

    fn write_pos(&self) -> &AtomicU64 {
        unsafe {
            &*(self.mmap.as_ptr().add(WRITE_POS_OFFSET) as *const CachePadded<AtomicU64>
                as *const AtomicU64)
        }
    }

    fn read_pos(&self) -> &AtomicU64 {
        unsafe {
            &*(self.mmap.as_ptr().add(READ_POS_OFFSET) as *const CachePadded<AtomicU64>
                as *const AtomicU64)
        }
    }

    fn data_ptr(&self) -> *const u8 {
        unsafe { self.mmap.as_ptr().add(HEADER_SIZE) }
    }

    fn data_mut_ptr(&mut self) -> *mut u8 {
        unsafe { self.mmap.as_mut_ptr().add(HEADER_SIZE) }
    }

    /// Publish a message. Returns true on success, false if the ring buffer is full.
    pub fn publish(&mut self, data: &[u8]) -> bool {
        let msg_len = data.len();
        let frame_len = align_up(FRAME_HEADER_SIZE + msg_len, ALIGNMENT);

        let write = self.write_pos().load(Ordering::Relaxed) as usize;
        let read = self.read_pos().load(Ordering::Acquire) as usize;

        // Available space (producer perspective)
        let used = write.wrapping_sub(read);
        if used + frame_len > self.capacity {
            return false;
        }

        let offset = write % self.capacity;

        // Check if we need to wrap
        if offset + frame_len > self.capacity {
            // Not enough contiguous space at end - write a padding frame and wrap
            let remaining = self.capacity - offset;
            if used + remaining + frame_len > self.capacity {
                return false;
            }
            // Write padding frame (length = 0 signals padding)
            let ptr = self.data_mut_ptr();
            unsafe {
                std::ptr::write(ptr.add(offset) as *mut u32, 0);
            }
            // Advance write position past padding
            let new_write = write + remaining;
            self.write_pos().store(new_write as u64, Ordering::Release);
            // Recurse with the wrapped position
            return self.publish(data);
        }

        let ptr = self.data_mut_ptr();
        unsafe {
            // Write length header
            std::ptr::write(ptr.add(offset) as *mut u32, msg_len as u32);
            // Write payload
            std::ptr::copy_nonoverlapping(
                data.as_ptr(),
                ptr.add(offset + FRAME_HEADER_SIZE),
                msg_len,
            );
        }

        // Release the write
        self.write_pos()
            .store((write + frame_len) as u64, Ordering::Release);

        true
    }

    /// Poll for the next message. Returns a byte slice if available.
    ///
    /// # Safety
    /// The returned slice borrows from the internal mmap. It is valid until
    /// the next call to `poll` or until the `MmapRingBuffer` is dropped.
    pub fn poll(&mut self) -> Option<&[u8]> {
        let read = self.read_pos().load(Ordering::Relaxed) as usize;
        let write = self.write_pos().load(Ordering::Acquire) as usize;

        if read == write {
            return None;
        }

        let offset = read % self.capacity;
        let ptr = self.data_ptr();

        let msg_len = unsafe { std::ptr::read(ptr.add(offset) as *const u32) } as usize;

        if msg_len == 0 {
            // Padding frame - skip to start of buffer
            let remaining = self.capacity - offset;
            self.read_pos()
                .store((read + remaining) as u64, Ordering::Release);
            return self.poll();
        }

        let frame_len = align_up(FRAME_HEADER_SIZE + msg_len, ALIGNMENT);
        let data =
            unsafe { std::slice::from_raw_parts(ptr.add(offset + FRAME_HEADER_SIZE), msg_len) };

        self.read_pos()
            .store((read + frame_len) as u64, Ordering::Release);

        Some(data)
    }
}

impl IpcPublisher for MmapRingBuffer {
    fn publish(&mut self, data: &[u8]) -> bool {
        MmapRingBuffer::publish(self, data)
    }
}

impl IpcSubscriber for MmapRingBuffer {
    fn poll(&mut self) -> Option<&[u8]> {
        MmapRingBuffer::poll(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_single_message() {
        let mut rb = MmapRingBuffer::new(4096);
        let payload = b"hello world";
        assert!(rb.publish(payload));

        let msg = rb.poll().expect("should have a message");
        assert_eq!(msg, payload);

        assert!(rb.poll().is_none());
    }

    #[test]
    fn test_multiple_messages() {
        let mut rb = MmapRingBuffer::new(4096);

        for i in 0..10u64 {
            let data = i.to_ne_bytes();
            assert!(rb.publish(&data));
        }

        for i in 0..10u64 {
            let msg = rb.poll().expect("should have a message");
            let val = u64::from_ne_bytes(msg.try_into().unwrap());
            assert_eq!(val, i);
        }

        assert!(rb.poll().is_none());
    }

    #[test]
    fn test_ring_buffer_full() {
        // Small buffer: 64 bytes of data region
        let mut rb = MmapRingBuffer::new(64);
        // Each 8-byte payload => frame = align_up(4+8, 8) = 16 bytes
        // Capacity 64 => 4 messages max
        assert!(rb.publish(&[0u8; 8]));
        assert!(rb.publish(&[0u8; 8]));
        assert!(rb.publish(&[0u8; 8]));
        assert!(rb.publish(&[0u8; 8]));
        // Should be full
        assert!(!rb.publish(&[0u8; 8]));

        // Drain one
        rb.poll().unwrap();
        // Should be able to publish one more
        assert!(rb.publish(&[0u8; 8]));
    }

    #[test]
    fn test_struct_message_roundtrip() {
        use crate::messages::VwapParamsMsg;

        let mut rb = MmapRingBuffer::new(4096);
        let mut msg = VwapParamsMsg::default();
        msg.symbol_index = 7;
        msg.num_buckets = 10;
        msg.participation_rate = 0.15;

        let bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                &msg as *const VwapParamsMsg as *const u8,
                std::mem::size_of::<VwapParamsMsg>(),
            )
        };
        assert!(rb.publish(bytes));

        let received = rb.poll().expect("should have message");
        let restored: &VwapParamsMsg = unsafe { &*(received.as_ptr() as *const VwapParamsMsg) };
        assert_eq!(restored.symbol_index, 7);
        assert_eq!(restored.num_buckets, 10);
        assert!((restored.participation_rate - 0.15).abs() < f64::EPSILON);
    }

    #[test]
    fn test_multithreaded_spsc() {
        const COUNT: u64 = 10_000;

        let capacity = 65536;
        let dir = std::env::temp_dir();
        let path = dir.join(format!("test_spsc_ring_buffer_{}", std::process::id()));

        // Clean up from previous runs
        let _ = std::fs::remove_file(&path);

        // Create producer (initializes header)
        let mut producer = MmapRingBuffer::create(&path, capacity);

        let path_clone = path.clone();
        let consumer_handle = std::thread::spawn(move || {
            // Small delay to let producer create the file
            std::thread::sleep(std::time::Duration::from_millis(50));
            let mut consumer = MmapRingBuffer::open(&path_clone, capacity);
            let mut received = 0u64;
            let mut attempts = 0u64;
            while received < COUNT {
                if let Some(data) = consumer.poll() {
                    let val = u64::from_ne_bytes(data.try_into().unwrap());
                    assert_eq!(val, received);
                    received += 1;
                    attempts = 0;
                } else {
                    attempts += 1;
                    if attempts > 10_000_000 {
                        panic!("consumer timed out at message {}", received);
                    }
                    std::thread::yield_now();
                }
            }
            received
        });

        for i in 0..COUNT {
            let data = i.to_ne_bytes();
            while !producer.publish(&data) {
                std::thread::yield_now();
            }
        }

        let total_received = consumer_handle.join().expect("consumer panicked");
        assert_eq!(total_received, COUNT);

        // Cleanup
        let _ = std::fs::remove_file(&path);
    }
}
