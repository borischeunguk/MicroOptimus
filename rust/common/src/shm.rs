use std::path::Path;

use memmap2::MmapMut;

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

