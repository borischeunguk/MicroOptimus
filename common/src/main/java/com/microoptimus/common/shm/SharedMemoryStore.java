package com.microoptimus.common.shm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * SharedMemoryStore - Direct memory-mapped store for market data payloads
 *
 * Design:
 * - Fixed-size entries in shared memory (/dev/shm on Linux, /tmp on Mac)
 * - Simple direct addressing: entry_id * entry_size
 * - No versioning, no headers - just raw data
 * - All processes map the same file
 * - Uses MappedByteBuffer directly (no Agrona dependency needed)
 *
 * Thread-Safety:
 * - Single writer (MDR) per entry
 * - Multiple readers (MM, OSM, etc.)
 * - No locking needed (write-once per entry ID)
 */
public class SharedMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SharedMemoryStore.class);

    // Entry structure: Fixed 32 bytes per entry
    // Offset 0-7:   bestBid (double)
    // Offset 8-15:  bestAsk (double)
    // Offset 16-23: timestamp (long)
    // Offset 24-27: venueId (int)
    // Offset 28-31: unused (padding)
    private static final int ENTRY_SIZE = 32;

    private final MappedByteBuffer buffer;

    /**
     * Create or open shared memory store
     *
     * @param path Full path to shared memory file (e.g., "/dev/shm/md.bin")
     * @param sizeBytes Total size in bytes (e.g., 128 * 1024 * 1024 for 128 MB)
     */
    public SharedMemoryStore(String path, long sizeBytes) throws IOException {
        log.info("Creating shared memory store: {} ({} MB)", path, sizeBytes / (1024 * 1024));

        // Create or open file
        FileChannel fc = FileChannel.open(
                Paths.get(path),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        fc.truncate(sizeBytes); // Pre-allocate

        // Memory map the file
        this.buffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, sizeBytes);

        // Set native byte order for best performance
        this.buffer.order(ByteOrder.nativeOrder());

        fc.close(); // Can close, mapping remains

        log.info("Shared memory store created: {}", path);
    }

    /**
     * Write market data entry (Producer: MDR)
     *
     * @param id Entry ID (used as direct index)
     * @param bestBid Best bid price
     * @param bestAsk Best ask price
     * @param timestamp Timestamp in nanoseconds
     * @param venueId Venue/exchange ID
     */
    public void writeEntry(int id, double bestBid, double bestAsk, long timestamp, int venueId) {
        int pos = id * ENTRY_SIZE;
        buffer.putDouble(pos, bestBid);
        buffer.putDouble(pos + 8, bestAsk);
        buffer.putLong(pos + 16, timestamp);
        buffer.putInt(pos + 24, venueId);

        // Force write to disk (ensures visibility across processes)
        buffer.force();
    }

    /**
     * Read market data entry (Consumer: MM, OSM)
     *
     * @param id Entry ID
     * @return MarketData object
     */
    public MarketData readEntry(int id) {
        int pos = id * ENTRY_SIZE;
        double bestBid = buffer.getDouble(pos);
        double bestAsk = buffer.getDouble(pos + 8);
        long timestamp = buffer.getLong(pos + 16);
        int venueId = buffer.getInt(pos + 24);

        return new MarketData(bestBid, bestAsk, timestamp, venueId);
    }

    /**
     * Market data record
     */
    public record MarketData(double bestBid, double bestAsk, long timestamp, int venueId) {
        @Override
        public String toString() {
            return String.format("MD[bid=%.2f, ask=%.2f, ts=%d, venue=%d]",
                    bestBid, bestAsk, timestamp, venueId);
        }
    }

    /**
     * Get entry size in bytes
     */
    public static int getEntrySize() {
        return ENTRY_SIZE;
    }

    /**
     * Close (cleanup if needed)
     */
    public void close() {
        // MappedByteBuffer cleanup is automatic
        log.info("Closed shared memory store");
    }
}

