use std::path::Path;

use memmap2::MmapMut;

use crate::sbe::MarketDataUpdate;
use crate::wire::ShmRef;

const HEADER_SIZE: usize = 64;
const ALIGNMENT: usize = 8;

fn align_up(value: usize) -> usize {
    (value + ALIGNMENT - 1) & !(ALIGNMENT - 1)
}

/// Single shared mmap region used as the data plane for algo <-> sor payloads.
pub struct SharedRegion {
    mmap: MmapMut,
    region_id: u32,
    capacity: usize,
    write_pos: usize,
    seq: u64,
}

impl SharedRegion {
    pub fn new_anon(region_id: u32, capacity: usize) -> Self {
        let mmap = MmapMut::map_anon(HEADER_SIZE + capacity)
            .expect("failed to create shared mmap region");
        Self {
            mmap,
            region_id,
            capacity,
            write_pos: 0,
            seq: 1,
        }
    }

    pub fn create<P: AsRef<Path>>(path: P, region_id: u32, capacity: usize) -> Self {
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)
            .expect("failed to open shared mmap file");
        file.set_len((HEADER_SIZE + capacity) as u64)
            .expect("failed to set mmap file length");

        let mmap = unsafe { MmapMut::map_mut(&file).expect("failed to map shared region file") };
        Self {
            mmap,
            region_id,
            capacity,
            write_pos: 0,
            seq: 1,
        }
    }

    pub fn open_existing<P: AsRef<Path>>(path: P, region_id: u32, capacity: usize) -> Self {
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .expect("failed to open existing shared mmap file");
        let expected_len = (HEADER_SIZE + capacity) as u64;
        let actual_len = file
            .metadata()
            .expect("failed to read mmap file metadata")
            .len();
        assert_eq!(
            actual_len, expected_len,
            "shared mmap file size mismatch: expected {expected_len}, got {actual_len}"
        );

        let mmap = unsafe { MmapMut::map_mut(&file).expect("failed to map shared region file") };
        Self {
            mmap,
            region_id,
            capacity,
            write_pos: 0,
            seq: 1,
        }
    }

    pub fn write(&mut self, msg_type: u16, payload: &[u8]) -> Option<ShmRef> {
        let frame_len = align_up(payload.len());
        if frame_len > self.capacity {
            return None;
        }

        if self.write_pos + frame_len > self.capacity {
            self.write_pos = 0;
        }

        let start = HEADER_SIZE + self.write_pos;
        let end = start + payload.len();
        self.mmap[start..end].copy_from_slice(payload);

        let shm_ref = ShmRef {
            region_id: self.region_id,
            msg_type,
            _pad: 0,
            offset: self.write_pos as u64,
            len: payload.len() as u32,
            _pad2: 0,
            seq: self.seq,
        };

        self.seq += 1;
        self.write_pos += frame_len;
        Some(shm_ref)
    }

    pub fn read(&self, shm_ref: &ShmRef) -> Option<&[u8]> {
        if shm_ref.region_id != self.region_id {
            return None;
        }
        let offset = shm_ref.offset as usize;
        let len = shm_ref.len as usize;
        if offset + len > self.capacity {
            return None;
        }

        let start = HEADER_SIZE + offset;
        let end = start + len;
        Some(&self.mmap[start..end])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_region_roundtrip() {
        let mut region = SharedRegion::new_anon(1, 4096);
        let payload = [10u8, 11, 12, 13];
        let r = region.write(2, &payload).unwrap();
        let restored = region.read(&r).unwrap();
        assert_eq!(restored, payload);
        assert_eq!(r.msg_type, 2);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MarketDataRegion — dedicated mmap region for per-symbol market-data snapshots
// ─────────────────────────────────────────────────────────────────────────────

/// Maximum number of symbols supported by `MarketDataRegion`.
pub const MD_MAX_SYMBOLS: usize = 16;

/// Each symbol slot is exactly 64 bytes (one cache line):
///   bytes [0..8]  = seqlock version counter (u64 LE)
///   bytes [8..64] = `MarketDataUpdate` (56 bytes)
const MD_SLOT_SIZE: usize = 64;

/// Total byte size of the market-data shared region.
pub const MD_SHM_CAPACITY: usize = MD_MAX_SYMBOLS * MD_SLOT_SIZE;

/// Dedicated shared-memory region for market-data snapshots.
///
/// Writer (marketdata service) calls [`write_snapshot`]; reader (SOR service)
/// calls [`read_snapshot`] / [`try_read_snapshot`].  Seqlock protocol guarantees
/// a torn-read-free view without any mutexes.
///
/// # Safety
/// Raw pointer arithmetic on a mmap-backed buffer.  Alignment is guaranteed by
/// the OS (page-aligned) which is ≥ 8 bytes needed by u64/AtomicU64.
pub struct MarketDataRegion {
    _mmap: MmapMut,
    base: *mut u8,
    num_symbols: usize,
}

// SAFETY: `MmapMut` is `!Send` but we access it only through atomic / volatile
// primitives protected by a seqlock, making cross-thread use safe.
unsafe impl Send for MarketDataRegion {}

impl MarketDataRegion {
    /// Create a new region backed by a file at `path` (truncates if exists).
    pub fn create(path: &str, num_symbols: usize) -> Self {
        let size = num_symbols * MD_SLOT_SIZE;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)
            .expect("MarketDataRegion: failed to create mmap file");
        file.set_len(size as u64)
            .expect("MarketDataRegion: failed to set file size");
        let mut mmap =
            unsafe { MmapMut::map_mut(&file).expect("MarketDataRegion: failed to mmap file") };
        // Zero the whole region so version counters start at 0 (= never written).
        mmap.iter_mut().for_each(|b| *b = 0);
        let base = mmap.as_mut_ptr();
        Self { _mmap: mmap, base, num_symbols }
    }

    /// Open an existing region file created by [`create`].
    pub fn open(path: &str, num_symbols: usize) -> Self {
        let size = num_symbols * MD_SLOT_SIZE;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .expect("MarketDataRegion: failed to open mmap file");
        assert_eq!(
            file.metadata().expect("metadata").len(),
            size as u64,
            "MarketDataRegion: file size mismatch"
        );
        let mut mmap =
            unsafe { MmapMut::map_mut(&file).expect("MarketDataRegion: failed to mmap file") };
        let base = mmap.as_mut_ptr();
        Self { _mmap: mmap, base, num_symbols }
    }

    #[inline]
    fn slot(&self, symbol: usize) -> *mut u8 {
        assert!(symbol < self.num_symbols, "symbol index out of range");
        // SAFETY: offset is within the mmap allocation.
        unsafe { self.base.add(symbol * MD_SLOT_SIZE) }
    }

    /// Seqlock writer: atomically overwrite the snapshot for `symbol`.
    ///
    /// Increments the version to odd (= writing), copies data, then sets it to
    /// even (= stable).  Uses `compiler_fence` + `write_volatile` which is
    /// correct on x86-64 (TSO); the compiler will not reorder across the fences.
    pub fn write_snapshot(&mut self, symbol: usize, snap: &MarketDataUpdate) {
        use std::sync::atomic::{compiler_fence, Ordering};
        let base = self.slot(symbol);
        // SAFETY: base is 64-byte aligned; version lives at offset 0 (u64); snap at offset 8.
        unsafe {
            let ver_ptr = base as *mut u64;
            let snap_ptr = base.add(8) as *mut MarketDataUpdate;
            let v = std::ptr::read_volatile(ver_ptr);
            // Mark slot as being written (odd).
            std::ptr::write_volatile(ver_ptr, v.wrapping_add(1));
            compiler_fence(Ordering::Release);
            std::ptr::write_volatile(snap_ptr, *snap);
            compiler_fence(Ordering::Release);
            // Mark slot as stable (even).
            std::ptr::write_volatile(ver_ptr, v.wrapping_add(2));
        }
    }

    /// Seqlock reader: spin until a consistent snapshot for `symbol` is obtained.
    ///
    /// Returns `None` if the slot has never been written (version == 0).
    pub fn try_read_snapshot(&self, symbol: usize) -> Option<MarketDataUpdate> {
        use std::sync::atomic::{compiler_fence, Ordering};
        let base = self.slot(symbol);
        loop {
            // SAFETY: same alignment guarantees as write_snapshot.
            unsafe {
                let ver_ptr = base as *const u64;
                let snap_ptr = base.add(8) as *const MarketDataUpdate;
                let v1 = std::ptr::read_volatile(ver_ptr);
                if v1 == 0 {
                    return None; // never written
                }
                if v1 & 1 != 0 {
                    // Writer is mid-flight; spin.
                    std::hint::spin_loop();
                    continue;
                }
                compiler_fence(Ordering::Acquire);
                let snap = std::ptr::read_volatile(snap_ptr);
                compiler_fence(Ordering::Acquire);
                let v2 = std::ptr::read_volatile(ver_ptr);
                if v1 == v2 {
                    return Some(snap);
                }
            }
            std::hint::spin_loop();
        }
    }
}

#[cfg(test)]
mod md_tests {
    use super::*;

    #[test]
    fn market_data_region_roundtrip() {
        let dir = std::env::temp_dir();
        let path = dir.join("mo_md_test_roundtrip.dat").to_string_lossy().into_owned();
        let snap = MarketDataUpdate {
            symbol_index: 0,
            _pad: 0,
            bid_price: 1_499,
            ask_price: 1_501,
            last_price: 1_500,
            bid_size: 10_000,
            ask_size: 8_000,
            timestamp: 42,
        };
        let mut region = MarketDataRegion::create(&path, MD_MAX_SYMBOLS);
        // Before any write → None
        assert!(region.try_read_snapshot(0).is_none());
        region.write_snapshot(0, &snap);
        let read = region.try_read_snapshot(0).expect("should have snapshot");
        assert_eq!(read.bid_price, 1_499);
        assert_eq!(read.ask_price, 1_501);
        assert_eq!(read.timestamp, 42);
        let _ = std::fs::remove_file(&path);
    }
}

