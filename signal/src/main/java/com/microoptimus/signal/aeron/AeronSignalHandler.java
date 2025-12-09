package com.microoptimus.signal.aeron;

import com.microoptimus.common.events.aeron.MdRefMessage;
import com.microoptimus.common.events.aeron.MarketDataPayload;
import com.microoptimus.common.shm.SharedMemoryStore;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AeronSignalHandler - Market Making Signal handler using Aeron + Shared Memory
 *
 * Architecture:
 * 1. Subscribe to Aeron IPC channel (receives tiny reference messages)
 * 2. Read payload directly from shared memory (zero-copy)
 * 3. Process market data (market making logic)
 *
 * Latency Measurement:
 * - Timestamp in reference message (from MDR)
 * - Compare with receive time
 */
public class AeronSignalHandler {

    private static final Logger log = LoggerFactory.getLogger(AeronSignalHandler.class);

    // Aeron configuration
    private static final String CHANNEL = "aeron:ipc";
    private static final int STREAM_ID = 1001;

    // Shared memory configuration
    private static final String SHM_NAME = "microoptimus-md";
    private static final int SLOT_SIZE = 128;
    private static final int NUM_SLOTS = 4096;

    private final Aeron aeron;
    private final Subscription subscription;
    private final SharedMemoryStore sharedMemory;
    private final byte[] payloadBuffer; // Reusable buffer

    // Metrics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong shmReadFailures = new AtomicLong(0);
    private final Histogram latencyHistogram;
    private boolean measurementPhase = false;

    // Running flag
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AeronSignalHandler() throws Exception {
        // Connect to existing shared memory
        log.info("Opening shared memory store...");
        String shmPath = "/tmp/md.bin";
        long shmSize = 128 * 1024 * 1024; // 128 MB
        this.sharedMemory = new SharedMemoryStore(shmPath, shmSize);

        // Pre-allocate payload buffer
        this.payloadBuffer = new byte[SLOT_SIZE];

        // Create Aeron client (connect to Media Driver started by MDR)
        log.info("Creating Aeron client...");
        Aeron.Context aeronContext = new Aeron.Context();
        this.aeron = Aeron.connect(aeronContext);

        // Create subscription
        log.info("Creating Aeron subscription: {} stream {}", CHANNEL, STREAM_ID);
        this.subscription = aeron.addSubscription(CHANNEL, STREAM_ID);

        // Wait for subscription to be connected
        while (!subscription.isConnected()) {
            Thread.sleep(1);
        }
        log.info("Aeron subscription connected");

        // Create latency histogram
        this.latencyHistogram = new Histogram(3_600_000_000_000L, 3);

        log.info("AeronSignalHandler initialized");
    }

    /**
     * Start measurement phase
     */
    public void startMeasurement() {
        measurementPhase = true;
        latencyHistogram.reset();
        messagesReceived.set(0);
        shmReadFailures.set(0);
        log.info("Measurement phase started");
    }

    /**
     * Stop measurement phase
     */
    public void stopMeasurement() {
        measurementPhase = false;
        log.info("Measurement phase stopped");
    }

    /**
     * Poll for messages (call repeatedly in loop)
     *
     * @return Number of fragments processed
     */
    public int poll() {
        return subscription.poll(this::onMessage, 10);
    }

    /**
     * Message handler (called by Aeron when message arrives)
     */
    private void onMessage(DirectBuffer buffer, int offset, int length, io.aeron.logbuffer.Header header) {
        long receiveTime = System.nanoTime();

        // Validate message size
        if (length != MdRefMessage.MESSAGE_SIZE) {
            log.error("Invalid message size: {} (expected {})", length, MdRefMessage.MESSAGE_SIZE);
            return;
        }

        // Decode reference message (4 bytes)
        MdRefMessage ref = MdRefMessage.decode(buffer, offset);

        // Read payload from shared memory (zero-copy)
        SharedMemoryStore.MarketData md = sharedMemory.readEntry(ref.id());

        // Calculate latency (MDR timestamp → now)
        long latencyNanos = receiveTime - md.timestamp();

        // Process market data (placeholder for market making logic)
        processMarketData(md, latencyNanos);

        // Update metrics
        messagesReceived.incrementAndGet();

        if (measurementPhase && latencyNanos > 0 && latencyNanos < 3_600_000_000_000L) {
            latencyHistogram.recordValue(latencyNanos);
        }
    }

    /**
     * Process market data (market making logic placeholder)
     */
    private void processMarketData(SharedMemoryStore.MarketData md, long latencyNanos) {
        // TODO: Implement market making strategy
        // For now, just validate data

        long count = messagesReceived.get();
        if (count % 100 == 0) {
            log.info("Processed MD #{}: {} | Latency: {} ns", count, md, latencyNanos);
        }
    }

    /**
     * Run polling loop (blocking)
     */
    public void run() {
        running.set(true);
        IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        log.info("Starting polling loop...");

        while (running.get()) {
            int fragmentsRead = poll();
            idleStrategy.idle(fragmentsRead);
        }

        log.info("Polling loop stopped");
    }

    /**
     * Stop polling loop
     */
    public void stop() {
        running.set(false);
    }

    /**
     * Get statistics
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getShmReadFailures() {
        return shmReadFailures.get();
    }

    /**
     * Print statistics
     */
    public void printStatistics() {
        long total = messagesReceived.get();
        long failures = shmReadFailures.get();

        log.info("=== Signal Handler Statistics ===");
        log.info("Messages received: {}", total);
        log.info("Shared memory read failures: {}", failures);
        log.info("Success rate: {:.2f}%", (total - failures) * 100.0 / total);

        if (latencyHistogram.getTotalCount() > 0) {
            log.info("=== Latency Statistics (MDR → Signal) ===");
            log.info("  Min:    {} ns", latencyHistogram.getMinValue());
            log.info("  Mean:   {} ns", String.format("%.0f", latencyHistogram.getMean()));
            log.info("  Median: {} ns", latencyHistogram.getValueAtPercentile(50.0));
            log.info("  P90:    {} ns", latencyHistogram.getValueAtPercentile(90.0));
            log.info("  P95:    {} ns", latencyHistogram.getValueAtPercentile(95.0));
            log.info("  P99:    {} ns", latencyHistogram.getValueAtPercentile(99.0));
            log.info("  P99.9:  {} ns", latencyHistogram.getValueAtPercentile(99.9));
            log.info("  P99.99: {} ns", latencyHistogram.getValueAtPercentile(99.99));
            log.info("  Max:    {} ns", latencyHistogram.getMaxValue());
        }
    }

    /**
     * Close and cleanup
     */
    public void close() {
        log.info("Closing AeronSignalHandler...");

        stop();

        if (subscription != null) {
            subscription.close();
        }

        if (aeron != null) {
            aeron.close();
        }

        if (sharedMemory != null) {
            sharedMemory.close();
        }

        log.info("AeronSignalHandler closed");
    }

    /**
     * Main method for standalone testing
     */
    public static void main(String[] args) throws Exception {
        AeronSignalHandler handler = new AeronSignalHandler();

        // Start measurement
        handler.startMeasurement();

        // Run for 5 seconds
        Thread runThread = new Thread(handler::run);
        runThread.start();

        Thread.sleep(5000);

        handler.stopMeasurement();
        handler.stop();
        runThread.join();

        handler.printStatistics();
        handler.close();
    }
}

