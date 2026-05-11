package com.microoptimus.javamvp.common;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real-Aeron sequencer using IPC pub/sub with no Cluster journal.
 * Round-trip latency ~200-500ns vs ~1ms+ for Aeron Cluster (WAL writes).
 * Suitable for JMH benchmarks measuring algo/routing throughput at 1M+ samples.
 */
public final class RealAeronIpcSequencer implements AutoCloseable {
    private static final int CLIENT_TO_SVC_STREAM = 10;
    private static final int SVC_TO_CLIENT_STREAM = 11;

    private final MediaDriver driver;
    private final Aeron aeron;
    private final Publication clientPub;
    private final Subscription clientSub;
    private final Publication servicePub;
    private final Subscription serviceSub;
    private final Thread serviceThread;
    private volatile boolean running = true;

    private final ExpandableArrayBuffer clientSendBuf = new ExpandableArrayBuffer(1024);
    private final ExpandableArrayBuffer svcEchoBuf = new ExpandableArrayBuffer(1024);
    private final IdleStrategy clientIdle = new YieldingIdleStrategy();
    private final IdleStrategy svcIdle = new YieldingIdleStrategy();
    private byte[] lastResponse;

    private final FragmentHandler responseHandler = (buffer, offset, length, header) -> {
        lastResponse = new byte[length];
        buffer.getBytes(offset, lastResponse);
    };

    public static RealAeronIpcSequencer launch(String uniqueId) {
        String aeronDir = new File(System.getProperty("java.io.tmpdir"),
            "javamvp-ipc-" + uniqueId + "-" + System.nanoTime() + "-"
                + ThreadLocalRandom.current().nextInt(1_000_000)).getAbsolutePath();

        MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.DEDICATED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));

        Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));

        Publication clientPub = aeron.addPublication("aeron:ipc", CLIENT_TO_SVC_STREAM);
        Subscription clientSub = aeron.addSubscription("aeron:ipc", SVC_TO_CLIENT_STREAM);
        Publication servicePub = aeron.addPublication("aeron:ipc", SVC_TO_CLIENT_STREAM);
        Subscription serviceSub = aeron.addSubscription("aeron:ipc", CLIENT_TO_SVC_STREAM);

        RealAeronIpcSequencer seq = new RealAeronIpcSequencer(
            driver, aeron, clientPub, clientSub, servicePub, serviceSub);
        seq.awaitConnected();
        return seq;
    }

    private RealAeronIpcSequencer(MediaDriver driver, Aeron aeron,
                                   Publication clientPub, Subscription clientSub,
                                   Publication servicePub, Subscription serviceSub) {
        this.driver = driver;
        this.aeron = aeron;
        this.clientPub = clientPub;
        this.clientSub = clientSub;
        this.servicePub = servicePub;
        this.serviceSub = serviceSub;

        FragmentHandler echoHandler = (buffer, offset, length, header) -> {
            svcEchoBuf.putBytes(0, buffer, offset, length);
            long r;
            do { r = servicePub.offer(svcEchoBuf, 0, length); } while (r <= 0 && running);
        };

        this.serviceThread = new Thread(() -> {
            while (running) {
                int count = serviceSub.poll(echoHandler, 10);
                svcIdle.idle(count);
            }
        }, "ipc-echo-svc");
        this.serviceThread.setDaemon(true);
        this.serviceThread.start();
    }

    private void awaitConnected() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (clientPub.isConnected()) return;
            Thread.yield();
        }
        throw new IllegalStateException("Timed out waiting for Aeron IPC connections to establish");
    }

    public byte[] sequenceRoundTrip(byte[] message) {
        clientSendBuf.putBytes(0, message);
        lastResponse = null;
        long result;
        do {
            result = clientPub.offer(clientSendBuf, 0, message.length);
        } while (result <= 0);

        while (lastResponse == null) {
            int count = clientSub.poll(responseHandler, 1);
            clientIdle.idle(count);
        }
        return lastResponse;
    }

    @Override
    public void close() {
        running = false;
        try { serviceThread.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        clientSub.close();
        clientPub.close();
        serviceSub.close();
        servicePub.close();
        aeron.close();
        driver.close();
    }
}
