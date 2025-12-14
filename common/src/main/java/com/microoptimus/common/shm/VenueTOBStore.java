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
 * VenueTOBStore - Enhanced shared memory store for venue top-of-book data
 * Designed specifically for Smart Order Router (SOR) with VWAP support
 *
 * Entry Structure (128 bytes per venue):
 * Offset 0-7:    venueId (long)
 * Offset 8-15:   bidPrice (double)
 * Offset 16-23:  askPrice (double)
 * Offset 24-31:  bidQty (long)
 * Offset 32-39:  askQty (long)
 * Offset 40-47:  lastUpdateTime (long)
 * Offset 48-55:  avgLatencyNanos (long)
 * Offset 56-63:  fillRate (long, scaled by 1M)
 * Offset 64-71:  feesPerShare (long, scaled by 1M)
 * Offset 72-79:  queuePosition (long)
 * Offset 80-87:  totalVolume24h (long)
 * Offset 88-95:  historicalFillTime (long)
 * Offset 96-127: reserved (padding)
 */
public class VenueTOBStore {

    private static final Logger log = LoggerFactory.getLogger(VenueTOBStore.class);

    // Entry structure: Fixed 128 bytes per venue for enhanced data
    private static final int ENTRY_SIZE = 128;

    // Venue IDs (matching C++ enum values)
    public static final int VENUE_INTERNAL = 1;
    public static final int VENUE_CME = 2;
    public static final int VENUE_NASDAQ = 3;
    public static final int VENUE_NYSE = 4;
    public static final int VENUE_ARCA = 5;
    public static final int VENUE_IEX = 6;
    public static final int VENUE_BATS = 7;

    private final MappedByteBuffer buffer;
    private final String storePath;

    /**
     * Create or open venue TOB shared memory store
     *
     * @param path Full path to shared memory file (e.g., "/tmp/venue_tob.bin")
     * @param maxVenues Maximum number of venues to support (e.g., 16)
     */
    public VenueTOBStore(String path, int maxVenues) throws IOException {
        this.storePath = path;
        long sizeBytes = (long) maxVenues * ENTRY_SIZE;

        log.info("Creating venue TOB store: {} ({} venues, {} KB)",
                path, maxVenues, sizeBytes / 1024);

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

        log.info("Venue TOB store created: {}", path);
    }

    /**
     * Write complete venue TOB entry (Producer: Internal matching engine, external gateways)
     */
    public void writeVenueTOB(int venueId, double bidPrice, double askPrice,
                             long bidQty, long askQty, long avgLatencyNanos,
                             long fillRate, long feesPerShare, long queuePosition) {
        int pos = venueId * ENTRY_SIZE;
        long timestamp = System.nanoTime();

        buffer.putLong(pos, venueId);
        buffer.putDouble(pos + 8, bidPrice);
        buffer.putDouble(pos + 16, askPrice);
        buffer.putLong(pos + 24, bidQty);
        buffer.putLong(pos + 32, askQty);
        buffer.putLong(pos + 40, timestamp);
        buffer.putLong(pos + 48, avgLatencyNanos);
        buffer.putLong(pos + 56, fillRate);
        buffer.putLong(pos + 64, feesPerShare);
        buffer.putLong(pos + 72, queuePosition);

        // Force write to ensure visibility across processes
        buffer.force();
    }

    /**
     * Read venue TOB entry (Consumer: SOR)
     */
    public VenueTOB readVenueTOB(int venueId) {
        int pos = venueId * ENTRY_SIZE;

        long id = buffer.getLong(pos);
        double bidPrice = buffer.getDouble(pos + 8);
        double askPrice = buffer.getDouble(pos + 16);
        long bidQty = buffer.getLong(pos + 24);
        long askQty = buffer.getLong(pos + 32);
        long lastUpdateTime = buffer.getLong(pos + 40);
        long avgLatencyNanos = buffer.getLong(pos + 48);
        long fillRate = buffer.getLong(pos + 56);
        long feesPerShare = buffer.getLong(pos + 64);
        long queuePosition = buffer.getLong(pos + 72);

        return new VenueTOB(id, bidPrice, askPrice, bidQty, askQty,
                           lastUpdateTime, avgLatencyNanos, fillRate,
                           feesPerShare, queuePosition);
    }

    /**
     * Bulk read all venues (for SOR venue scoring)
     */
    public VenueTOB[] readAllVenues(int maxVenues) {
        VenueTOB[] venues = new VenueTOB[maxVenues];
        for (int i = 0; i < maxVenues; i++) {
            venues[i] = readVenueTOB(i);
        }
        return venues;
    }

    /**
     * Write performance update (for historical metrics)
     */
    public void updateVenuePerformance(int venueId, long actualLatencyNanos,
                                      long actualFillRate, long newQueuePosition) {
        int pos = venueId * ENTRY_SIZE;

        // Update performance metrics
        buffer.putLong(pos + 48, actualLatencyNanos);
        buffer.putLong(pos + 56, actualFillRate);
        buffer.putLong(pos + 72, newQueuePosition);
        buffer.putLong(pos + 40, System.nanoTime()); // Update timestamp

        buffer.force();
    }

    /**
     * Venue Top-of-Book record with enhanced SOR data
     */
    public record VenueTOB(
        long venueId,
        double bidPrice,
        double askPrice,
        long bidQty,
        long askQty,
        long lastUpdateTime,
        long avgLatencyNanos,
        long fillRate,          // Scaled by 1M (e.g., 950000 = 95%)
        long feesPerShare,      // Scaled by 1M (e.g., 2000 = $0.002)
        long queuePosition
    ) {

        public double getFillRatePercent() {
            return fillRate / 1000000.0;
        }

        public double getFeesPerShareDollars() {
            return feesPerShare / 1000000.0;
        }

        public long getLatencyMicros() {
            return avgLatencyNanos / 1000;
        }

        public boolean isStale(long maxAgeNanos) {
            return (System.nanoTime() - lastUpdateTime) > maxAgeNanos;
        }

        @Override
        public String toString() {
            return String.format("Venue[%d: bid=%.4f@%d, ask=%.4f@%d, lat=%dμs, fill=%.1f%%, fees=$%.4f]",
                    venueId, bidPrice, bidQty, askPrice, askQty,
                    getLatencyMicros(), getFillRatePercent(), getFeesPerShareDollars());
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
        log.info("Closed venue TOB store: {}", storePath);
    }
}
