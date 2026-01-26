package com.microoptimus.algo.cluster;

import com.microoptimus.algo.engine.AlgoEngine;
import com.microoptimus.algo.engine.AlgoSORBridge;
import com.microoptimus.algo.model.AlgoOrder;
import com.microoptimus.algo.model.AlgoOrder.AlgorithmType;
import com.microoptimus.algo.model.AlgoParameters;
import com.microoptimus.algo.slice.Slice;
import com.microoptimus.common.types.Side;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AlgoClusterService - Aeron Cluster service for algorithmic order execution
 *
 * Responsibilities:
 * - Receives algo order requests from cluster
 * - Manages algo order lifecycle
 * - Generates slices and routes through SOR
 * - Publishes status updates
 */
public class AlgoClusterService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(AlgoClusterService.class);

    // Core components
    private final AlgoEngine algoEngine;
    private final AlgoSORBridge sorBridge;

    // Response buffer
    private final MutableDirectBuffer responseBuffer;

    // Cluster state
    private Cluster cluster;
    private long lastSequenceNumber;

    // Processing interval
    private static final long PROCESS_INTERVAL_NS = 1_000_000; // 1ms
    private long lastProcessTime;

    public AlgoClusterService() {
        this.algoEngine = new AlgoEngine();
        this.sorBridge = new AlgoSORBridge();
        this.responseBuffer = new ExpandableDirectByteBuffer(512);

        // Wire up callbacks
        algoEngine.setSliceCallback(this::onSliceGenerated);
        algoEngine.setOrderUpdateCallback(this::onOrderUpdate);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        log.info("Algo Cluster Service started, role={}", cluster.role());

        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.debug("Session opened: sessionId={}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.debug("Session closed: sessionId={}, reason={}", session.id(), closeReason);
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp,
                                  DirectBuffer buffer, int offset, int length,
                                  Header header) {
        int messageType = buffer.getInt(offset);
        offset += 4;

        switch (messageType) {
            case 1: // AlgoOrderRequest
                handleAlgoOrderRequest(session, timestamp, buffer, offset);
                break;
            case 2: // AlgoControlCommand
                handleAlgoControlCommand(session, timestamp, buffer, offset);
                break;
            case 3: // SliceExecutionReport
                handleSliceExecutionReport(buffer, offset);
                break;
            default:
                log.warn("Unknown message type: {}", messageType);
        }

        lastSequenceNumber = header.position();
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Process active orders periodically
        if (timestamp - lastProcessTime >= PROCESS_INTERVAL_NS) {
            // Get current market price (would come from market data feed)
            long currentPrice = 10000; // Placeholder
            algoEngine.processOrders(timestamp, currentPrice);
            lastProcessTime = timestamp;
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        log.info("Taking algo snapshot...");
        // Snapshot algo order state
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Algo role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("Algo Cluster Service terminating");
    }

    /**
     * Handle new algo order request
     */
    private void handleAlgoOrderRequest(ClientSession session, long timestamp,
                                         DirectBuffer buffer, int offset) {
        // Decode (simplified - would use SBE)
        long sequenceId = buffer.getLong(offset); offset += 8;
        long clientId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        int sideOrdinal = buffer.getInt(offset); offset += 4;
        long totalQuantity = buffer.getLong(offset); offset += 8;
        long limitPrice = buffer.getLong(offset); offset += 8;
        int algoTypeOrdinal = buffer.getInt(offset); offset += 4;
        long startTime = buffer.getLong(offset); offset += 8;
        long endTime = buffer.getLong(offset); offset += 8;

        // Create default parameters (would be decoded from message)
        AlgoParameters params = switch (AlgorithmType.values()[algoTypeOrdinal]) {
            case TWAP -> AlgoParameters.twap(100, 10000, 1000);
            case VWAP -> AlgoParameters.vwap(100, 10000, 10, 0.10);
            case ICEBERG -> AlgoParameters.iceberg(100, 0);
            default -> new AlgoParameters();
        };

        // Submit order
        AlgoOrder order = algoEngine.submitOrder(
                clientId, symbolIndex,
                Side.values()[sideOrdinal],
                totalQuantity, limitPrice,
                AlgorithmType.values()[algoTypeOrdinal],
                params, startTime, endTime, timestamp
        );

        // Start immediately if start time has passed
        if (timestamp >= startTime) {
            algoEngine.startOrder(order.getAlgoOrderId(), timestamp);
        }

        // Send acknowledgment
        sendOrderAck(session, order);
    }

    /**
     * Handle algo control command
     */
    private void handleAlgoControlCommand(ClientSession session, long timestamp,
                                           DirectBuffer buffer, int offset) {
        long sequenceId = buffer.getLong(offset); offset += 8;
        long algoOrderId = buffer.getLong(offset); offset += 8;
        int commandOrdinal = buffer.getInt(offset);

        boolean success = switch (commandOrdinal) {
            case 0 -> algoEngine.startOrder(algoOrderId, timestamp);   // START
            case 1 -> algoEngine.pauseOrder(algoOrderId, timestamp);   // PAUSE
            case 2 -> algoEngine.resumeOrder(algoOrderId, timestamp);  // RESUME
            case 3 -> algoEngine.cancelOrder(algoOrderId, timestamp);  // CANCEL
            default -> false;
        };

        // Send command response
        sendCommandResponse(session, algoOrderId, commandOrdinal, success);
    }

    /**
     * Handle slice execution report from SOR
     */
    private void handleSliceExecutionReport(DirectBuffer buffer, int offset) {
        long sliceId = buffer.getLong(offset); offset += 8;
        long execQty = buffer.getLong(offset); offset += 8;
        long execPrice = buffer.getLong(offset); offset += 8;
        long timestamp = buffer.getLong(offset);

        algoEngine.onSliceExecution(sliceId, execQty, execPrice, timestamp);
    }

    /**
     * Callback when slice is generated
     */
    private void onSliceGenerated(Slice slice) {
        // Route through SOR bridge
        sorBridge.routeSlice(slice);
    }

    /**
     * Callback when order state changes
     */
    private void onOrderUpdate(AlgoOrder order) {
        // Would publish status update to cluster
        log.debug("Order update: {}", order);
    }

    /**
     * Send order acknowledgment
     */
    private void sendOrderAck(ClientSession session, AlgoOrder order) {
        if (session == null) return;

        int offset = 0;
        responseBuffer.putInt(offset, 101); // AlgoOrderAck
        offset += 4;
        responseBuffer.putLong(offset, order.getAlgoOrderId());
        offset += 8;
        responseBuffer.putInt(offset, order.getState().ordinal());
    }

    /**
     * Send command response
     */
    private void sendCommandResponse(ClientSession session, long orderId, int command, boolean success) {
        if (session == null) return;

        int offset = 0;
        responseBuffer.putInt(offset, 102); // CommandResponse
        offset += 4;
        responseBuffer.putLong(offset, orderId);
        offset += 8;
        responseBuffer.putInt(offset, command);
        offset += 4;
        responseBuffer.putInt(offset, success ? 1 : 0);
    }

    /**
     * Load snapshot for recovery
     */
    private void loadSnapshot(Image snapshotImage) {
        log.info("Loading algo snapshot...");
    }

    // Accessors
    public AlgoEngine getAlgoEngine() { return algoEngine; }
    public AlgoSORBridge getSORBridge() { return sorBridge; }
    public long getLastSequenceNumber() { return lastSequenceNumber; }
}
