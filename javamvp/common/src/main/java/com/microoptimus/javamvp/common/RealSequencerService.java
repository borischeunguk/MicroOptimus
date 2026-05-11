package com.microoptimus.javamvp.common;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

import java.util.HashSet;
import java.util.Set;

/** Stateless single-node clustered service that sequences and echoes ingress messages to all sessions. */
public final class RealSequencerService implements ClusteredService {
    private final Set<ClientSession> sessions = new HashSet<>();

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        // no-op
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        sessions.add(session);
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        sessions.remove(session);
    }

    @Override
    public void onSessionMessage(
        ClientSession session,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header
    ) {
        for (ClientSession s : sessions) {
            s.offer(buffer, offset, length);
        }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // no-op
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // no state
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        // no-op
    }

    @Override
    public void onTerminate(Cluster cluster) {
        // no-op
    }
}

