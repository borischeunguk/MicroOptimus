package com.microoptimus.common.sbe;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SBE Message Usage Tests - Comprehensive unit tests demonstrating zero-JNI SBE architecture
 *
 * These tests show how to:
 * 1. Encode/decode SBE messages for OSM ↔ SOR communication
 * 2. Use shared memory for zero-copy message passing
 * 3. Implement different routing algorithms via SBE
 * 4. Handle various order types and venue allocations
 * 5. Achieve zero-JNI latency optimization
 */
public class SBEMessageUsageTest {

    private static final Logger log = LoggerFactory.getLogger(SBEMessageUsageTest.class);

    private SBEBufferManager bufferManager;

    @BeforeEach
    void setUp() throws IOException {
        // Create in-memory SBE buffer manager for testing
        bufferManager = new SBEBufferManager("/tmp/sbe_test.bin", 1024);

        log.info("🧪 SBE Message Usage Test Setup Complete");
    }

    @Test
    @DisplayName("🚀 Test Basic OrderRequest SBE Message Encoding/Decoding")
    void testOrderRequestMessageFlow() {
        log.info("Testing OrderRequest message encoding/decoding...");

        // Test data - typical VWAP order
        long sequenceId = 12345L;
        long orderId = 67890L;
        String symbol = "AAPL";
        byte side = 0; // BUY
        byte orderType = 1; // LIMIT
        long price = 15050000L; // $15.05 scaled by 1M
        long quantity = 5000L;
        long timestamp = System.nanoTime();
        byte algorithm = 3; // VWAP
        long maxLatency = 50_000L; // 50μs max
        int clientId = 12345;

        // Step 1: OSM encodes OrderRequest to shared memory
        MutableDirectBuffer osmBuffer = bufferManager.getWriteBuffer(sequenceId);
        int encodedLength = encodeOrderRequest(osmBuffer, sequenceId, orderId, symbol,
                side, orderType, price, quantity, timestamp, algorithm, maxLatency, clientId);

        // Publish to shared memory
        bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

        log.info("📤 OSM encoded OrderRequest: seq={}, length={} bytes", sequenceId, encodedLength);

        // Step 2: SOR reads message from shared memory
        SBEBufferManager.MessageInfo messageInfo = bufferManager.getMessageInfo(sequenceId);
        assertNotNull(messageInfo, "Message should be available in shared memory");
        assertEquals(sequenceId, messageInfo.sequenceId, "Sequence ID should match");
        assertEquals(SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, messageInfo.templateId, "Template ID should match");

        UnsafeBuffer sorBuffer = bufferManager.getReadBuffer(sequenceId);
        OrderRequestData decoded = decodeOrderRequest(sorBuffer);

        log.info("🔍 SOR decoded OrderRequest: {}", decoded);

        // Verify all fields
        assertEquals(sequenceId, decoded.sequenceId, "Sequence ID should match");
        assertEquals(orderId, decoded.orderId, "Order ID should match");
        assertEquals(symbol, decoded.symbol, "Symbol should match");
        assertEquals(side, decoded.side, "Side should match");
        assertEquals(orderType, decoded.orderType, "Order type should match");
        assertEquals(price, decoded.price, "Price should match");
        assertEquals(quantity, decoded.quantity, "Quantity should match");
        assertEquals(algorithm, decoded.algorithm, "Algorithm should match");
        assertEquals(maxLatency, decoded.maxLatencyNanos, "Max latency should match");
        assertEquals(clientId, decoded.clientId, "Client ID should match");

        log.info("✅ OrderRequest encoding/decoding test passed");
    }

    @Test
    @DisplayName("🎯 Test RoutingDecision with Multi-Venue Allocation")
    void testRoutingDecisionWithVenueAllocation() {
        log.info("Testing RoutingDecision with venue allocation...");

        long sequenceId = 12346L;
        long orderId = 67890L;
        byte action = 2; // SPLIT_ORDER
        long estimatedFillTime = 75_000L; // 75μs
        long totalQuantity = 5000L;
        long estimatedFees = 15_000L; // $0.015 scaled
        int confidence = 92; // 92% confidence

        // Multi-venue allocation: Internal + NASDAQ + ARCA
        VenueAllocation[] allocations = {
            new VenueAllocation(1, 2000L, 1, 5_000L, 0L, 100), // INTERNAL: 2000 shares, 5μs, no fees, 100% fill
            new VenueAllocation(3, 2000L, 2, 45_000L, 8_000L, 95), // NASDAQ: 2000 shares, 45μs, $0.008 fees, 95% fill
            new VenueAllocation(5, 1000L, 3, 40_000L, 7_500L, 90)  // ARCA: 1000 shares, 40μs, $0.0075 fees, 90% fill
        };

        // Step 1: SOR encodes routing decision
        MutableDirectBuffer sorBuffer = bufferManager.getWriteBuffer(sequenceId);
        int encodedLength = encodeRoutingDecision(sorBuffer, sequenceId, orderId, action,
                estimatedFillTime, totalQuantity, estimatedFees, confidence, "", allocations);

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, encodedLength);

        log.info("📤 SOR encoded RoutingDecision: seq={}, length={} bytes", sequenceId, encodedLength);

        // Step 2: OSM reads routing decision
        SBEBufferManager.MessageInfo messageInfo = bufferManager.getMessageInfo(sequenceId);
        assertNotNull(messageInfo, "Message should be available");
        assertEquals(SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, messageInfo.templateId);

        UnsafeBuffer osmBuffer = bufferManager.getReadBuffer(sequenceId);
        RoutingDecisionData decision = decodeRoutingDecision(osmBuffer);

        log.info("🔍 OSM decoded RoutingDecision: {}", decision);

        // Verify decision fields
        assertEquals(sequenceId, decision.sequenceId);
        assertEquals(orderId, decision.orderId);
        assertEquals(action, decision.action);
        assertEquals(estimatedFillTime, decision.estimatedFillTime);
        assertEquals(totalQuantity, decision.totalQuantity);
        assertEquals(confidence, decision.confidence);
        assertEquals(3, decision.allocations.length);

        // Verify venue allocations
        assertEquals(1, decision.allocations[0].venueId); // INTERNAL
        assertEquals(2000L, decision.allocations[0].quantity);
        assertEquals(1, decision.allocations[0].priority);
        assertEquals(100, decision.allocations[0].fillProbability);

        assertEquals(3, decision.allocations[1].venueId); // NASDAQ
        assertEquals(2000L, decision.allocations[1].quantity);
        assertEquals(45_000L, decision.allocations[1].estimatedLatency);

        log.info("✅ Multi-venue routing decision test passed");
    }

    @Test
    @DisplayName("⚡ Test High-Frequency Trading Scenario")
    void testHighFrequencyTradingScenario() {
        log.info("Testing high-frequency trading scenario...");

        // Simulate rapid-fire order routing
        long startTime = System.nanoTime();
        int messageCount = 1000;

        for (int i = 0; i < messageCount; i++) {
            long sequenceId = 100_000L + i;
            long orderId = 200_000L + i;

            // Encode order request
            MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);
            int encodedLength = encodeOrderRequest(buffer, sequenceId, orderId, "MSFT",
                    (byte)(i % 2), (byte)1, 25000000L + (i * 1000), 100L,
                    System.nanoTime(), (byte)1, 10_000L, 999);

            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

            // Simulate SOR processing and response
            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            OrderRequestData request = decodeOrderRequest(readBuffer);
            assertNotNull(request);

            // Quick routing decision
            long responseSeq = sequenceId + messageCount;
            MutableDirectBuffer responseBuffer = bufferManager.getWriteBuffer(responseSeq);
            VenueAllocation[] quickAllocation = { new VenueAllocation(3, request.quantity, 1, 50_000L, 5_000L, 95) };

            int responseLength = encodeRoutingDecision(responseBuffer, responseSeq, orderId,
                    (byte)0, 25_000L, request.quantity, 5_000L, 98, "", quickAllocation);

            bufferManager.publishMessage(responseSeq, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, responseLength);
        }

        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgLatencyPerMessage = totalTime / (double)(messageCount * 2); // encode + decode

        log.info("🚀 HFT Performance: {} messages processed in {}μs", messageCount * 2, totalTime / 1000);
        log.info("📊 Average latency per message: {}ns", String.format("%.1f", avgLatencyPerMessage));

        // Performance assertions
        assertTrue(avgLatencyPerMessage < 1000, "Average latency should be < 1μs per message");
        assertTrue(totalTime < 10_000_000, "Total processing should be < 10ms for 2000 messages");

        log.info("✅ High-frequency trading scenario passed");
    }

    @Test
    @DisplayName("🔄 Test Different Routing Algorithms")
    void testDifferentRoutingAlgorithms() {
        log.info("Testing different routing algorithms...");

        // Test each algorithm type
        byte[] algorithms = {1, 2, 3, 4, 5}; // SIMPLE, TWAP, VWAP, POV, ICEBERG
        String[] algorithmNames = {"SIMPLE", "TWAP", "VWAP", "POV", "ICEBERG"};

        for (int i = 0; i < algorithms.length; i++) {
            long sequenceId = 50_000L + i;

            MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);
            int encodedLength = encodeOrderRequest(buffer, sequenceId, 80000L + i, "GOOGL",
                    (byte)0, (byte)1, 280000000L, 1000L, System.nanoTime(),
                    algorithms[i], 100_000L, 777);

            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            OrderRequestData request = decodeOrderRequest(readBuffer);

            assertEquals(algorithms[i], request.algorithm,
                    "Algorithm should match for " + algorithmNames[i]);

            log.info("📋 Algorithm {}: {} - encoded and decoded successfully",
                    algorithms[i], algorithmNames[i]);
        }

        log.info("✅ All routing algorithms test passed");
    }

    @Test
    @DisplayName("❌ Test Order Rejection Scenario")
    void testOrderRejectionScenario() {
        log.info("Testing order rejection scenario...");

        long sequenceId = 99999L;
        long orderId = 88888L;
        String rejectReason = "Insufficient liquidity - market impact > 5%";

        // Encode rejection
        MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);
        int encodedLength = encodeRoutingDecision(buffer, sequenceId, orderId,
                (byte)3, 0L, 0L, 0L, 0, rejectReason, new VenueAllocation[0]); // REJECT

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, encodedLength);

        // Decode and verify rejection
        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        RoutingDecisionData decision = decodeRoutingDecision(readBuffer);

        assertEquals(3, decision.action, "Action should be REJECT");
        assertEquals(0, decision.allocations.length, "No allocations for rejected order");
        assertEquals(rejectReason, decision.rejectReason.trim(), "Reject reason should match");

        log.info("📤 Rejection encoded: {}", rejectReason);
        log.info("🔍 Rejection decoded: {}", decision.rejectReason.trim());
        log.info("✅ Order rejection test passed");
    }

    // Helper methods for SBE encoding/decoding (manual implementation for demonstration)
    // In production, these would be replaced by generated SBE classes

    private int encodeOrderRequest(MutableDirectBuffer buffer, long sequenceId, long orderId,
            String symbol, byte side, byte orderType, long price, long quantity,
            long timestamp, byte algorithm, long maxLatency, int clientId) {

        int offset = 0;

        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;

        // Symbol (16 chars)
        byte[] symbolBytes = symbol.getBytes();
        for (int i = 0; i < 16; i++) {
            buffer.putByte(offset + i, i < symbolBytes.length ? symbolBytes[i] : (byte)0);
        }
        offset += 16;

        buffer.putByte(offset, side); offset += 1;
        buffer.putByte(offset, orderType); offset += 1;
        buffer.putLong(offset, price); offset += 8;
        buffer.putLong(offset, quantity); offset += 8;
        buffer.putLong(offset, timestamp); offset += 8;
        buffer.putByte(offset, algorithm); offset += 1;
        buffer.putLong(offset, maxLatency); offset += 8;
        buffer.putInt(offset, clientId); offset += 4;

        return offset;
    }

    private OrderRequestData decodeOrderRequest(UnsafeBuffer buffer) {
        int offset = 0;

        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;

        byte[] symbolBytes = new byte[16];
        buffer.getBytes(offset, symbolBytes);
        String symbol = new String(symbolBytes).trim();
        offset += 16;

        byte side = buffer.getByte(offset); offset += 1;
        byte orderType = buffer.getByte(offset); offset += 1;
        long price = buffer.getLong(offset); offset += 8;
        long quantity = buffer.getLong(offset); offset += 8;
        long timestamp = buffer.getLong(offset); offset += 8;
        byte algorithm = buffer.getByte(offset); offset += 1;
        long maxLatencyNanos = buffer.getLong(offset); offset += 8;
        int clientId = buffer.getInt(offset); offset += 4;

        return new OrderRequestData(sequenceId, orderId, symbol, side, orderType,
                price, quantity, timestamp, algorithm, maxLatencyNanos, clientId);
    }

    private int encodeRoutingDecision(MutableDirectBuffer buffer, long sequenceId, long orderId,
            byte action, long estimatedFillTime, long totalQuantity, long estimatedFees,
            int confidence, String rejectReason, VenueAllocation[] allocations) {

        int offset = 0;

        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;
        buffer.putByte(offset, action); offset += 1;
        buffer.putLong(offset, estimatedFillTime); offset += 8;
        buffer.putLong(offset, totalQuantity); offset += 8;
        buffer.putLong(offset, estimatedFees); offset += 8;
        buffer.putInt(offset, confidence); offset += 4;

        // Reject reason (64 chars)
        if (rejectReason != null) {
            byte[] reasonBytes = rejectReason.getBytes();
            for (int i = 0; i < 64; i++) {
                buffer.putByte(offset + i, i < reasonBytes.length ? reasonBytes[i] : (byte)0);
            }
        }
        offset += 64;

        // Number of allocations
        buffer.putInt(offset, allocations.length); offset += 4;

        // Venue allocations
        for (VenueAllocation allocation : allocations) {
            buffer.putByte(offset, (byte)allocation.venueId); offset += 1;
            buffer.putLong(offset, allocation.quantity); offset += 8;
            buffer.putByte(offset, (byte)allocation.priority); offset += 1;
            buffer.putLong(offset, allocation.estimatedLatency); offset += 8;
            buffer.putLong(offset, allocation.estimatedFees); offset += 8;
            buffer.putInt(offset, allocation.fillProbability); offset += 4;
        }

        return offset;
    }

    private RoutingDecisionData decodeRoutingDecision(UnsafeBuffer buffer) {
        int offset = 0;

        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;
        byte action = buffer.getByte(offset); offset += 1;
        long estimatedFillTime = buffer.getLong(offset); offset += 8;
        long totalQuantity = buffer.getLong(offset); offset += 8;
        long estimatedFees = buffer.getLong(offset); offset += 8;
        int confidence = buffer.getInt(offset); offset += 4;

        // Reject reason
        byte[] reasonBytes = new byte[64];
        buffer.getBytes(offset, reasonBytes);
        String rejectReason = new String(reasonBytes);
        offset += 64;

        // Allocations
        int numAllocations = buffer.getInt(offset); offset += 4;
        VenueAllocation[] allocations = new VenueAllocation[numAllocations];

        for (int i = 0; i < numAllocations; i++) {
            int venueId = buffer.getByte(offset) & 0xFF; offset += 1;
            long quantity = buffer.getLong(offset); offset += 8;
            int priority = buffer.getByte(offset) & 0xFF; offset += 1;
            long estimatedLatency = buffer.getLong(offset); offset += 8;
            long fees = buffer.getLong(offset); offset += 8;
            int fillProbability = buffer.getInt(offset); offset += 4;

            allocations[i] = new VenueAllocation(venueId, quantity, priority, estimatedLatency, fees, fillProbability);
        }

        return new RoutingDecisionData(sequenceId, orderId, action, estimatedFillTime,
                totalQuantity, estimatedFees, confidence, rejectReason, allocations);
    }

    // Data classes for testing
    static class OrderRequestData {
        final long sequenceId, orderId, price, quantity, timestamp, maxLatencyNanos;
        final String symbol;
        final byte side, orderType, algorithm;
        final int clientId;

        OrderRequestData(long sequenceId, long orderId, String symbol, byte side, byte orderType,
                        long price, long quantity, long timestamp, byte algorithm,
                        long maxLatencyNanos, int clientId) {
            this.sequenceId = sequenceId;
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.orderType = orderType;
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
            this.algorithm = algorithm;
            this.maxLatencyNanos = maxLatencyNanos;
            this.clientId = clientId;
        }

        @Override
        public String toString() {
            return String.format("OrderRequest[id=%d, %s %s %d@%.2f, algo=%s, client=%d]",
                    orderId, symbol, side == 0 ? "BUY" : "SELL", quantity, price / 1_000_000.0,
                    getAlgorithmName(algorithm), clientId);
        }

        private String getAlgorithmName(byte algo) {
            return switch(algo) {
                case 1 -> "SIMPLE";
                case 2 -> "TWAP";
                case 3 -> "VWAP";
                case 4 -> "POV";
                case 5 -> "ICEBERG";
                default -> "UNKNOWN";
            };
        }
    }

    static class RoutingDecisionData {
        final long sequenceId, orderId, estimatedFillTime, totalQuantity, estimatedFees;
        final byte action;
        final int confidence;
        final String rejectReason;
        final VenueAllocation[] allocations;

        RoutingDecisionData(long sequenceId, long orderId, byte action, long estimatedFillTime,
                           long totalQuantity, long estimatedFees, int confidence,
                           String rejectReason, VenueAllocation[] allocations) {
            this.sequenceId = sequenceId;
            this.orderId = orderId;
            this.action = action;
            this.estimatedFillTime = estimatedFillTime;
            this.totalQuantity = totalQuantity;
            this.estimatedFees = estimatedFees;
            this.confidence = confidence;
            this.rejectReason = rejectReason;
            this.allocations = allocations;
        }

        @Override
        public String toString() {
            if (action == 3) { // REJECT
                return String.format("REJECTED[id=%d]: %s", orderId, rejectReason.trim());
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("RoutingDecision[id=%d, action=%s, qty=%d, confidence=%d%%, allocations=[",
                    orderId, getActionName(action), totalQuantity, confidence));

            for (int i = 0; i < allocations.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(allocations[i]);
            }
            sb.append("]]");
            return sb.toString();
        }

        private String getActionName(byte action) {
            return switch(action) {
                case 0 -> "EXTERNAL";
                case 1 -> "INTERNAL";
                case 2 -> "SPLIT";
                case 3 -> "REJECT";
                default -> "UNKNOWN";
            };
        }
    }

    static class VenueAllocation {
        final int venueId, priority, fillProbability;
        final long quantity, estimatedLatency, estimatedFees;

        VenueAllocation(int venueId, long quantity, int priority, long estimatedLatency,
                       long estimatedFees, int fillProbability) {
            this.venueId = venueId;
            this.quantity = quantity;
            this.priority = priority;
            this.estimatedLatency = estimatedLatency;
            this.estimatedFees = estimatedFees;
            this.fillProbability = fillProbability;
        }

        @Override
        public String toString() {
            return String.format("%s:%d@%dμs(%.0f%%)",
                    getVenueName(venueId), quantity, estimatedLatency/1000, fillProbability/100.0);
        }

        private String getVenueName(int venueId) {
            return switch(venueId) {
                case 1 -> "INTERNAL";
                case 2 -> "CME";
                case 3 -> "NASDAQ";
                case 4 -> "NYSE";
                case 5 -> "ARCA";
                case 6 -> "IEX";
                default -> "VENUE" + venueId;
            };
        }
    }
}
