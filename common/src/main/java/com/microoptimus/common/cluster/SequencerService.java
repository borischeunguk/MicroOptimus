package com.microoptimus.common.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SequencerService - Global Sequencer using Aeron Cluster
 *
 * Role: ClusteredService that provides total ordering for MD reference messages
 *
 * Flow:
 * 1. MDR sends MdRefMessage (4 bytes) to cluster ingress
 * 2. Leader receives via onSessionMessage()
 * 3. GLOBAL ORDER: Already guaranteed by Aeron Cluster log sequencing
 * 4. Leader forwards message to egress (cluster handles replication)
 * 5. Followers automatically replay the same ordered log
 *
 * Key Points:
 * - Sequencer does NOT read shared memory
 * - Sequencer only orders the reference message (MD_ID)
 * - No data manipulation needed
 */
public class SequencerService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(SequencerService.class);

    private Cluster cluster;

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        log.info("SequencerService started, role={}, memberId={}",
                cluster.role(), cluster.memberId());
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.info("Session opened: sessionId={}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.info("Session closed: sessionId={}, reason={}", session.id(), closeReason);
    }

    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {

        // GLOBAL SEQUENCE: Use header.position() as the unique global sequence number
        long globalSequence = header.position();

        // Log periodically for debugging
        long sessionId = (session != null) ? session.id() : -1;
        if (globalSequence % 100 == 0 || globalSequence <= 10) {
            log.info("SequencerService processed message #{} from session {}", globalSequence, sessionId);
        }

        // GLOBAL ORDER: Already guaranteed by Aeron Cluster log sequencing
        // Forward the message to egress for all clients
        // (Cluster handles replication + sending optimized)

        long result = cluster.offer(buffer, offset, length);

        if (result < 0) {
            log.warn("Failed to offer message to cluster: {}", result);
        }
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Not used
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        // No state to snapshot (stateless sequencer)
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("SequencerService terminating");
    }
}

