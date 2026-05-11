package com.microoptimus.javamvp.common;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** Single mmap region used by algo and sor for payload exchange. */
public final class MmapSharedRegion {
    private static final int HEADER_SIZE = 64;
    private static final int ALIGNMENT = 8;

    private final int regionId;
    private final int capacity;
    private final MappedByteBuffer buffer;
    private long writeOffset;
    private long seq;

    public MmapSharedRegion(Path filePath, int regionId, int capacity) throws IOException {
        this.regionId = regionId;
        this.capacity = capacity;
        this.writeOffset = 0;
        this.seq = 1;

        Files.createDirectories(filePath.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.setLength(HEADER_SIZE + capacity);
            try (FileChannel channel = raf.getChannel()) {
                this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_SIZE + capacity);
            }
        }
    }

    public synchronized ShmRef write(int msgType, byte[] payload) {
        int aligned = align(payload.length);
        if (aligned > capacity) {
            throw new IllegalArgumentException("payload too large for region");
        }
        if (writeOffset + aligned > capacity) {
            writeOffset = 0;
        }

        int start = (int) (HEADER_SIZE + writeOffset);
        ByteBuffer dup = buffer.duplicate();
        dup.position(start);
        dup.put(payload);

        ShmRef ref = new ShmRef(regionId, msgType, writeOffset, payload.length, seq++);
        writeOffset += aligned;
        return ref;
    }

    public synchronized byte[] read(ShmRef ref) {
        if (ref.regionId != regionId) {
            throw new IllegalArgumentException("region mismatch");
        }
        if (ref.offset + ref.length > capacity) {
            throw new IllegalArgumentException("ref out of bounds");
        }
        byte[] out = new byte[ref.length];
        int start = (int) (HEADER_SIZE + ref.offset);
        ByteBuffer dup = buffer.duplicate();
        dup.position(start);
        dup.get(out);
        return out;
    }

    private static int align(int len) {
        return (len + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    }
}

