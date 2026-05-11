package com.microoptimus.javamvp.common;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Single-node Aeron-cluster style sequencer wrapper used for MVP benchmarks.
 */
public final class AeronStyleSequencer {
    private final Queue<byte[]> queue = new ArrayDeque<>();

    public void publish(byte[] message) {
        queue.add(message);
    }

    public byte[] poll() {
        return queue.poll();
    }
}

