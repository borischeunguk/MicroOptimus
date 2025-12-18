package com.microoptimus.common.sbe;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * SBEBufferManager - Manages SBE message buffers in shared memory
 *
 * Zero-JNI Architecture:
 * 1. OSM encodes SBE message to shared memory buffer
 * 2. OSM sends lightweight notification via Aeron
 * 3. SOR decodes SBE message from shared memory buffer (zero-copy)
 * 4. SOR encodes response to shared memory buffer
 * 5. SOR sends lightweight notification via Aeron
 * 6. OSM decodes response from shared memory buffer (zero-copy)
 */
public class SBEBufferManager {

    private static final Logger log = LoggerFactory.getLogger(SBEBufferManager.class);

    private static final int MESSAGE_SLOT_SIZE = 1024;  // 1KB per message
    private static final int HEADER_SIZE = 16;          // sequence + length + templateId + reserved
    private static final int MAX_PAYLOAD_SIZE = MESSAGE_SLOT_SIZE - HEADER_SIZE; // 1008 bytes

    // Header offsets
    private static final int SEQUENCE_ID_OFFSET = 0;    // 8 bytes
    private static final int MESSAGE_LENGTH_OFFSET = 8; // 4 bytes
    private static final int TEMPLATE_ID_OFFSET = 12;   // 2 bytes
    private static final int RESERVED_OFFSET = 14;      // 2 bytes (for alignment)

    // SBE Template IDs (matching our schemas)
    public static final short ORDER_REQUEST_TEMPLATE_ID = 1;
    public static final short ROUTING_DECISION_TEMPLATE_ID = 2;
    public static final short MARKET_DATA_SNAPSHOT_TEMPLATE_ID = 1;  // Different schema ID
    public static final short EXECUTION_REPORT_TEMPLATE_ID = 1;      // Different schema ID

    private final MappedByteBuffer sharedBuffer;
    private final MutableDirectBuffer directBuffer;
    private final int numSlots;
    private final String storePath;

    public SBEBufferManager(String path, int maxMessages) throws IOException {
        this.storePath = path;
        this.numSlots = maxMessages;
        long totalSize = (long) maxMessages * MESSAGE_SLOT_SIZE;

        log.info("Creating SBE buffer manager: {} ({} message slots, {} MB)",
                path, maxMessages, totalSize / (1024 * 1024));

        // Create or open memory-mapped file
        FileChannel fc = FileChannel.open(
                Paths.get(path),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        fc.truncate(totalSize);
        this.sharedBuffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
        this.sharedBuffer.order(ByteOrder.nativeOrder());
        this.directBuffer = new UnsafeBuffer(sharedBuffer);

        fc.close();
        log.info("SBE buffer manager created: {}", path);
    }

    /**
     * Get buffer for writing SBE message at sequence position
     */
    public MutableDirectBuffer getWriteBuffer(long sequenceId) {
        int slot = (int)(sequenceId % numSlots);
        int payloadOffset = (slot * MESSAGE_SLOT_SIZE) + HEADER_SIZE;
        return new UnsafeBuffer(directBuffer, payloadOffset, MAX_PAYLOAD_SIZE);
    }

    /**
     * Get buffer for reading SBE message at sequence position
     */
    public UnsafeBuffer getReadBuffer(long sequenceId) {
        int slot = (int)(sequenceId % numSlots);
        int payloadOffset = (slot * MESSAGE_SLOT_SIZE) + HEADER_SIZE;
        return new UnsafeBuffer(directBuffer, payloadOffset, MAX_PAYLOAD_SIZE);
    }

    /**
     * Mark SBE message ready for consumption
     */
    public void publishMessage(long sequenceId, short templateId, int messageLength) {
        if (messageLength > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Message too large: " + messageLength);
        }

        int slot = (int)(sequenceId % numSlots);
        int headerOffset = slot * MESSAGE_SLOT_SIZE;

        // Write header atomically
        directBuffer.putLong(headerOffset + SEQUENCE_ID_OFFSET, sequenceId);
        directBuffer.putInt(headerOffset + MESSAGE_LENGTH_OFFSET, messageLength);
        directBuffer.putShort(headerOffset + TEMPLATE_ID_OFFSET, templateId);
        directBuffer.putShort(headerOffset + RESERVED_OFFSET, (short) 0);

        // Force memory barrier for cross-process visibility
        sharedBuffer.force();
    }

    /**
     * Check if SBE message is ready and get details
     */
    public MessageInfo getMessageInfo(long sequenceId) {
        int slot = (int)(sequenceId % numSlots);
        int headerOffset = slot * MESSAGE_SLOT_SIZE;

        long storedSeq = directBuffer.getLong(headerOffset + SEQUENCE_ID_OFFSET);
        if (storedSeq != sequenceId) {
            return null; // Message not ready or overwritten
        }

        int messageLength = directBuffer.getInt(headerOffset + MESSAGE_LENGTH_OFFSET);
        short templateId = directBuffer.getShort(headerOffset + TEMPLATE_ID_OFFSET);

        return new MessageInfo(sequenceId, templateId, messageLength);
    }

    public void close() {
        log.info("Closed SBE buffer manager: {}", storePath);
    }

    // Data classes
    public static class MessageInfo {
        public final long sequenceId;
        public final short templateId;
        public final int messageLength;

        public MessageInfo(long sequenceId, short templateId, int messageLength) {
            this.sequenceId = sequenceId;
            this.templateId = templateId;
            this.messageLength = messageLength;
        }

        @Override
        public String toString() {
            return String.format("MessageInfo[seq=%d, template=%d, length=%d]",
                    sequenceId, templateId, messageLength);
        }
    }
}
