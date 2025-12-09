package com.microoptimus.common.events.aeron;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * MdRefMessage - Tiny reference message sent through Aeron Cluster
 *
 * Contains ONLY the entry ID - actual payload is in shared memory
 *
 * Message Layout (4 bytes):
 * - id: 4 bytes (int) - Entry ID in shared memory store
 *
 * Total: 4 bytes (minimal overhead through cluster)
 */
public class MdRefMessage {

    public static final int MESSAGE_SIZE = 4;

    private final int id;

    public MdRefMessage(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /**
     * Encode reference message into buffer
     */
    public static int encode(MdRefMessage msg, MutableDirectBuffer buffer, int offset) {
        buffer.putInt(offset, msg.id());
        return MESSAGE_SIZE;
    }

    /**
     * Decode reference message from buffer
     */
    public static MdRefMessage decode(DirectBuffer buffer, int offset) {
        return new MdRefMessage(buffer.getInt(offset));
    }

    @Override
    public String toString() {
        return String.format("MdRef[id=%d]", id);
    }
}

