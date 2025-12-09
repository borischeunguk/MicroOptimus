package com.microoptimus.recombinor.aeron;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * AeronRecombinor - Market Data Recombinor using Aeron + Shared Memory
 *
 * Architecture:
 * 1. Receive raw market data (simulated)
 * 2. Write payload to shared memory
 * 3. Send tiny reference message via Aeron IPC
 *
 * Zero-copy: Consumers read payload directly from shared memory
 */
public class AeronRecombinor {

    private static final Logger log = LoggerFactory.getLogger(AeronRecombinor.class);

    // Aeron configuration
    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1001;


    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final Publication publication;
    private final SharedMemoryStore sharedMemory;
    private final UnsafeBuffer encodeBuffer;

    private int nextId = 0;
    private final String symbol;

    public AeronRecombinor(String symbol) throws Exception {
        this.symbol = symbol;

        // Create Media Driver (embedded)
        log.info("Starting Media Driver...");
        MediaDriver.Context driverContext = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .threadingMode(io.aeron.driver.ThreadingMode.SHARED);

        this.mediaDriver = MediaDriver.launch(driverContext);

        // Create Aeron client
        log.info("Creating Aeron client...");
        Aeron.Context aeronContext = new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName());

        this.aeron = Aeron.connect(aeronContext);

        // Create publication
        log.info("Creating Aeron publication: {} stream {}", CHANNEL, STREAM_ID);
        this.publication = aeron.addPublication(CHANNEL, STREAM_ID);

        // Wait for publication to be connected
        while (!publication.isConnected()) {
            Thread.sleep(1);
        }
        log.info("Aeron publication connected");

        // Create shared memory
        log.info("Creating shared memory store...");
        String shmPath = "/tmp/md.bin";
        long shmSize = 128 * 1024 * 1024; // 128 MB
        this.sharedMemory = new SharedMemoryStore(shmPath, shmSize);

        // Pre-allocate encode buffer
        this.encodeBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MdRefMessage.MESSAGE_SIZE));

        log.info("AeronRecombinor initialized for symbol: {}", symbol);
    }

    /**
     * Publish market data update
     *
     * @param bidPrice Best bid price
     * @param bidSize Best bid size
     * @param askPrice Best ask price
     * @param askSize Best ask size
     * @return Sequence number
     */
    public long publishMarketData(long bidPrice, long bidSize, long askPrice, long askSize) {
        long timestamp = System.nanoTime();
        int id = nextId++;

        // 1. Write payload to shared memory (32 bytes)
        sharedMemory.writeEntry(id, bidPrice, askPrice, timestamp, 1); // venue=1

        // 2. Encode reference message (4 bytes)
        MdRefMessage ref = new MdRefMessage(id);
        MdRefMessage.encode(ref, encodeBuffer, 0);

        // 3. Publish reference via Aeron
        long result = publication.offer(encodeBuffer, 0, MdRefMessage.MESSAGE_SIZE);

        if (result < 0) {
            if (result == Publication.BACK_PRESSURED) {
                log.warn("Publication back-pressured");
            } else if (result == Publication.NOT_CONNECTED) {
                log.error("Publication not connected");
            } else {
                log.error("Publication failed: {}", result);
            }
        }

        return id;
    }

    /**
     * Generate synthetic market data for testing
     */
    public void generateSyntheticData(int count, long delayNanos) {
        log.info("Generating {} synthetic market data events", count);

        long startTime = System.nanoTime();

        // Starting prices
        long bidPrice = 150_00; // $150.00
        long askPrice = 150_10; // $150.10

        for (int i = 0; i < count; i++) {
            // Simulate price movement
            if (i % 10 == 0) {
                bidPrice += (i % 20 == 0) ? -5 : 5;
                askPrice = bidPrice + 10;
            }

            publishMarketData(bidPrice, 100, askPrice, 100);

            // Optional delay
            if (delayNanos > 0) {
                long target = System.nanoTime() + delayNanos;
                while (System.nanoTime() < target) {
                    // Busy spin
                }
            }
        }

        long endTime = System.nanoTime();
        long durationNanos = endTime - startTime;
        double durationMs = durationNanos / 1_000_000.0;
        double throughput = (count * 1_000_000_000.0) / durationNanos;

        log.info("Published {} events in {} ms ({} msgs/sec)",
                count, String.format("%.2f", durationMs), String.format("%.0f", throughput));
    }

    /**
     * Get statistics
     */
    public long getEventsPublished() {
        return nextId;
    }

    public SharedMemoryStore getSharedMemory() {
        return sharedMemory;
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing AeronRecombinor...");

        if (publication != null) {
            publication.close();
        }

        if (aeron != null) {
            aeron.close();
        }

        if (mediaDriver != null) {
            mediaDriver.close();
        }

        if (sharedMemory != null) {
            sharedMemory.close();
        }

        log.info("AeronRecombinor closed");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        AeronRecombinor recombinor = new AeronRecombinor("AAPL");

        // Generate test data
        recombinor.generateSyntheticData(1000, 0);

        // Keep running for a bit
        Thread.sleep(5000);

        recombinor.close();
    }
}

