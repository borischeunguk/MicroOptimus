package com.microoptimus.javamvp.common;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

/**
 * Cross-process IPC transport wrappers used by coordinator/algo/sor services.
 */
public final class CrossProcessAeronIpcTransport implements AutoCloseable {
    private final Aeron aeron;

    private CrossProcessAeronIpcTransport(Aeron aeron) {
        this.aeron = aeron;
    }

    public static CrossProcessAeronIpcTransport connect(String aeronDir) {
        Aeron client = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        return new CrossProcessAeronIpcTransport(client);
    }

    public BlockingPublication addPublication(int streamId) {
        return new BlockingPublication(aeron.addPublication(E2EIpcConfig.CHANNEL, streamId));
    }

    public BlockingSubscription addSubscription(int streamId) {
        return new BlockingSubscription(aeron.addSubscription(E2EIpcConfig.CHANNEL, streamId));
    }

    @Override
    public void close() {
        aeron.close();
    }

    public static final class TimeoutException extends Exception {
        private static final long serialVersionUID = 1L;

        public TimeoutException(String message) {
            super(message);
        }
    }

    public static final class BlockingPublication implements AutoCloseable {
        private final Publication publication;
        private final ExpandableArrayBuffer sendBuffer = new ExpandableArrayBuffer(1024);
        private final IdleStrategy idle = new YieldingIdleStrategy();

        private BlockingPublication(Publication publication) {
            this.publication = publication;
        }

        public void awaitConnected(long timeoutNs) throws TimeoutException {
            long deadline = System.nanoTime() + timeoutNs;
            while (System.nanoTime() < deadline) {
                if (publication.isConnected()) {
                    return;
                }
                Thread.yield();
            }
            throw new TimeoutException("Timed out waiting for publication to connect");
        }

        public long offerBlocking(byte[] payload, long timeoutNs) throws TimeoutException {
            sendBuffer.putBytes(0, payload);
            long deadline = System.nanoTime() + timeoutNs;
            long maxBlockNs = 0L;
            while (true) {
                long start = System.nanoTime();
                long result = publication.offer(sendBuffer, 0, payload.length);
                long blocked = System.nanoTime() - start;
                if (blocked > maxBlockNs) {
                    maxBlockNs = blocked;
                }
                if (result > 0) {
                    return maxBlockNs;
                }
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("Timed out while offering message");
                }
                idle.idle(0);
            }
        }

        @Override
        public void close() {
            publication.close();
        }
    }

    public static final class BlockingSubscription implements AutoCloseable {
        private final Subscription subscription;
        private final IdleStrategy idle = new YieldingIdleStrategy();
        private byte[] lastMessage;
        private final FragmentHandler handler = (buffer, offset, length, header) -> {
            lastMessage = new byte[length];
            buffer.getBytes(offset, lastMessage);
        };

        private BlockingSubscription(Subscription subscription) {
            this.subscription = subscription;
        }

        public void awaitConnected(long timeoutNs) throws TimeoutException {
            long deadline = System.nanoTime() + timeoutNs;
            while (System.nanoTime() < deadline) {
                if (subscription.imageCount() > 0) {
                    return;
                }
                Thread.yield();
            }
            throw new TimeoutException("Timed out waiting for subscription image");
        }

        public PollResult pollBlocking(long timeoutNs) throws TimeoutException {
            long deadline = System.nanoTime() + timeoutNs;
            long maxBlockNs = 0L;
            while (true) {
                long start = System.nanoTime();
                int count = subscription.poll(handler, 1);
                long blocked = System.nanoTime() - start;
                if (blocked > maxBlockNs) {
                    maxBlockNs = blocked;
                }
                if (count > 0 && lastMessage != null) {
                    byte[] out = lastMessage;
                    lastMessage = null;
                    return new PollResult(out, maxBlockNs);
                }
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("Timed out while polling message");
                }
                idle.idle(count);
            }
        }

        public byte[] poll() {
            int count = subscription.poll(handler, 1);
            if (count > 0 && lastMessage != null) {
                byte[] out = lastMessage;
                lastMessage = null;
                return out;
            }
            return null;
        }

        @Override
        public void close() {
            subscription.close();
        }
    }

    public static final class PollResult {
        public final byte[] payload;
        public final long blockNs;

        public PollResult(byte[] payload, long blockNs) {
            this.payload = payload;
            this.blockNs = blockNs;
        }
    }
}
