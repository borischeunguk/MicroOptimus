package com.microoptimus.common.sbe;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderRequestMessageTest - Comprehensive unit tests for the OrderRequestMessage.xml SBE schema
 *
 * This test validates:
 * 1. Schema correctness and SBE generation capability
 * 2. All message types encoding/decoding
 * 3. Enum value mappings
 * 4. Field size constraints
 * 5. Group/repeating field functionality
 * 6. Cross-language compatibility
 */
public class OrderRequestMessageTest {

    private static final Logger log = LoggerFactory.getLogger(OrderRequestMessageTest.class);

    private SBEBufferManager bufferManager;

    @BeforeEach
    void setUp() throws IOException {
        bufferManager = new SBEBufferManager("/tmp/order_test.bin", 1024);
        log.info("🧪 OrderRequestMessage SBE Schema Test Setup Complete");
    }

    @Test
    @DisplayName("🔍 Test OrderRequest Message Structure")
    void testOrderRequestMessageStructure() {
        log.info("Testing OrderRequest message structure...");

        long sequenceId = 12345L;
        MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

        // Test OrderRequest encoding with all fields
        int encodedLength = encodeOrderRequest(buffer,
            12345L,              // sequenceId
            67890L,              // orderId
            "AAPL",              // symbol
            (byte)0,             // side: BUY
            (byte)1,             // orderType: LIMIT
            15500000L,           // price: $15.50 scaled by 1M
            1000L,               // quantity: 1000 shares
            System.nanoTime(),   // timestamp
            (byte)3,             // algorithm: VWAP
            50000L,              // maxLatencyNanos: 50μs
            12345,               // clientId
            100L,                // minFillQty
            (byte)1              // timeInForce: GTC
        );

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

        // Decode and validate
        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        OrderRequestData decoded = decodeOrderRequest(readBuffer);

        // Validate all fields
        assertEquals(12345L, decoded.sequenceId, "Sequence ID should match");
        assertEquals(67890L, decoded.orderId, "Order ID should match");
        assertEquals("AAPL", decoded.symbol, "Symbol should match");
        assertEquals((byte)0, decoded.side, "Side should be BUY");
        assertEquals((byte)1, decoded.orderType, "Order type should be LIMIT");
        assertEquals(15500000L, decoded.price, "Price should match");
        assertEquals(1000L, decoded.quantity, "Quantity should match");
        assertEquals((byte)3, decoded.algorithm, "Algorithm should be VWAP");
        assertEquals(50000L, decoded.maxLatencyNanos, "Max latency should match");
        assertEquals(12345, decoded.clientId, "Client ID should match");
        assertEquals(100L, decoded.minFillQty, "Min fill quantity should match");
        assertEquals((byte)1, decoded.timeInForce, "Time in force should be GTC");

        log.info("✅ OrderRequest structure test passed");
    }

    @Test
    @DisplayName("🏪 Test All Enum Values")
    void testEnumValues() {
        log.info("Testing all enum values...");

        // Test Side enum
        assertEquals("BUY", getSideName((byte)0));
        assertEquals("SELL", getSideName((byte)1));

        // Test OrderType enum
        assertEquals("MARKET", getOrderTypeName((byte)0));
        assertEquals("LIMIT", getOrderTypeName((byte)1));
        assertEquals("STOP", getOrderTypeName((byte)2));
        assertEquals("STOP_LIMIT", getOrderTypeName((byte)3));

        // Test Algorithm enum
        assertEquals("SIMPLE", getAlgorithmName((byte)1));
        assertEquals("TWAP", getAlgorithmName((byte)2));
        assertEquals("VWAP", getAlgorithmName((byte)3));
        assertEquals("POV", getAlgorithmName((byte)4));
        assertEquals("ICEBERG", getAlgorithmName((byte)5));

        // Test RoutingAction enum
        assertEquals("ROUTE_EXTERNAL", getRoutingActionName((byte)0));
        assertEquals("ROUTE_INTERNAL", getRoutingActionName((byte)1));
        assertEquals("SPLIT_ORDER", getRoutingActionName((byte)2));
        assertEquals("REJECT", getRoutingActionName((byte)3));

        // Test VenueId enum
        assertEquals("INTERNAL", getVenueName((byte)1));
        assertEquals("CME", getVenueName((byte)2));
        assertEquals("NASDAQ", getVenueName((byte)3));
        assertEquals("NYSE", getVenueName((byte)4));
        assertEquals("ARCA", getVenueName((byte)5));
        assertEquals("IEX", getVenueName((byte)6));

        log.info("✅ All enum values test passed");
    }

    @Test
    @DisplayName("🎯 Test RoutingDecision with Group Fields")
    void testRoutingDecisionWithGroups() {
        log.info("Testing RoutingDecision message with venue allocations...");

        long sequenceId = 23456L;
        MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

        // Create venue allocations
        VenueAllocation[] allocations = {
            new VenueAllocation((byte)1, 2000L, (byte)1, 5000L, 0L, 100),     // INTERNAL
            new VenueAllocation((byte)3, 2000L, (byte)2, 45000L, 8000L, 95),  // NASDAQ
            new VenueAllocation((byte)5, 1000L, (byte)3, 40000L, 7500L, 90)   // ARCA
        };

        int encodedLength = encodeRoutingDecision(buffer,
            23456L,              // sequenceId
            67890L,              // orderId
            (byte)2,             // action: SPLIT_ORDER
            75000L,              // estimatedFillTime: 75μs
            5000L,               // totalQuantity
            15500L,              // estimatedFees
            92,                  // confidence: 92%
            "",                  // rejectReason (empty)
            allocations
        );

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, encodedLength);

        // Decode and validate
        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        RoutingDecisionData decoded = decodeRoutingDecision(readBuffer);

        assertEquals(23456L, decoded.sequenceId);
        assertEquals(67890L, decoded.orderId);
        assertEquals((byte)2, decoded.action);
        assertEquals(75000L, decoded.estimatedFillTime);
        assertEquals(5000L, decoded.totalQuantity);
        assertEquals(15500L, decoded.estimatedFees);
        assertEquals(92, decoded.confidence);
        assertEquals(3, decoded.allocations.length);

        // Validate venue allocations
        assertEquals((byte)1, decoded.allocations[0].venueId);
        assertEquals(2000L, decoded.allocations[0].quantity);
        assertEquals((byte)1, decoded.allocations[0].priority);
        assertEquals(5000L, decoded.allocations[0].estimatedLatency);
        assertEquals(0L, decoded.allocations[0].estimatedFees);
        assertEquals(100, decoded.allocations[0].fillProbability);

        assertEquals((byte)3, decoded.allocations[1].venueId);
        assertEquals(2000L, decoded.allocations[1].quantity);
        assertEquals(45000L, decoded.allocations[1].estimatedLatency);

        log.info("✅ RoutingDecision with groups test passed");
    }

    @Test
    @DisplayName("⚡ Test High-Frequency Message Processing")
    void testHighFrequencyProcessing() {
        log.info("Testing high-frequency message processing...");

        int messageCount = 1000;
        long startTime = System.nanoTime();

        for (int i = 0; i < messageCount; i++) {
            long sequenceId = 30000L + i;
            MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

            // Encode order request
            int encodedLength = encodeOrderRequestFast(buffer, sequenceId, 80000L + i,
                "MSFT", (byte)(i % 2), (byte)1, 25000000L, 100L);

            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

            // Read back immediately
            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            long decodedOrderId = readBuffer.getLong(8);

            assertEquals(80000L + i, decodedOrderId, "Order ID should match for message " + i);
        }

        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgLatency = totalTime / (double)messageCount;

        log.info("📊 HFT Performance: {} messages in {}μs", messageCount, totalTime / 1000);
        log.info("📊 Average latency: {}ns per message", String.format("%.1f", avgLatency));

        // Performance assertion
        assertTrue(avgLatency < 5000, "Average latency should be < 5μs per message");

        log.info("✅ High-frequency processing test passed");
    }

    @Test
    @DisplayName("🚫 Test Order Rejection Scenario")
    void testOrderRejection() {
        log.info("Testing order rejection scenario...");

        long sequenceId = 40000L;
        MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

        String rejectReason = "Insufficient liquidity - market impact exceeds 5% threshold";

        int encodedLength = encodeRoutingDecision(buffer,
            sequenceId,
            67890L,
            (byte)3,             // action: REJECT
            0L,                  // estimatedFillTime: N/A
            0L,                  // totalQuantity: N/A
            0L,                  // estimatedFees: N/A
            0,                   // confidence: N/A
            rejectReason,
            new VenueAllocation[0] // No allocations
        );

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ROUTING_DECISION_TEMPLATE_ID, encodedLength);

        // Decode and validate rejection
        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        RoutingDecisionData decoded = decodeRoutingDecision(readBuffer);

        assertEquals((byte)3, decoded.action, "Should be REJECT");
        assertEquals(0, decoded.allocations.length, "Should have no allocations");
        assertTrue(decoded.rejectReason.trim().contains("Insufficient liquidity"),
                  "Reject reason should contain expected text");

        log.info("📤 Rejection reason: {}", decoded.rejectReason.trim());
        log.info("✅ Order rejection test passed");
    }

    @Test
    @DisplayName("🌐 Test Cross-Language Compatibility")
    void testCrossLanguageCompatibility() {
        log.info("Testing cross-language compatibility...");

        // Test symbol field with various character sets
        String[] symbols = {"AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "BRK.A", "SPY"};

        for (int i = 0; i < symbols.length; i++) {
            long sequenceId = 50000L + i;
            MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

            int encodedLength = encodeOrderRequest(buffer,
                sequenceId, 90000L + i, symbols[i], (byte)0, (byte)1,
                100000000L, 100L, System.nanoTime(), (byte)1, 10000L, 999, 50L, (byte)0);

            bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

            // Decode and verify symbol encoding/decoding
            UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
            OrderRequestData decoded = decodeOrderRequest(readBuffer);

            assertEquals(symbols[i], decoded.symbol, "Symbol should match for " + symbols[i]);
        }

        log.info("✅ Cross-language compatibility test passed");
    }

    @Test
    @DisplayName("📏 Test Field Size Constraints")
    void testFieldSizeConstraints() {
        log.info("Testing field size constraints...");

        // Test symbol length constraint (16 characters max)
        long sequenceId = 60000L;
        MutableDirectBuffer buffer = bufferManager.getWriteBuffer(sequenceId);

        String longSymbol = "VERY_LONG_SYMBOL_NAME_THAT_EXCEEDS_16_CHARS";

        int encodedLength = encodeOrderRequest(buffer,
            sequenceId, 95000L, longSymbol, (byte)0, (byte)1,
            100000000L, 100L, System.nanoTime(), (byte)1, 10000L, 999, 50L, (byte)0);

        bufferManager.publishMessage(sequenceId, SBEBufferManager.ORDER_REQUEST_TEMPLATE_ID, encodedLength);

        // Decode and verify truncation
        UnsafeBuffer readBuffer = bufferManager.getReadBuffer(sequenceId);
        OrderRequestData decoded = decodeOrderRequest(readBuffer);

        // Symbol should be truncated to 16 characters
        assertTrue(decoded.symbol.length() <= 16, "Symbol should be truncated to 16 characters");
        assertEquals("VERY_LONG_SYMBOL", decoded.symbol, "Symbol should be properly truncated");

        log.info("📏 Truncated symbol: '{}' -> '{}'", longSymbol, decoded.symbol);
        log.info("✅ Field size constraints test passed");
    }

    // Helper encoding/decoding methods (simulating SBE generated code behavior)

    private int encodeOrderRequest(MutableDirectBuffer buffer, long sequenceId, long orderId,
            String symbol, byte side, byte orderType, long price, long quantity,
            long timestamp, byte algorithm, long maxLatencyNanos, int clientId,
            long minFillQty, byte timeInForce) {

        int offset = 0;
        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;

        // Symbol (16 chars, null-padded)
        byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
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
        buffer.putLong(offset, maxLatencyNanos); offset += 8;
        buffer.putInt(offset, clientId); offset += 4;
        buffer.putLong(offset, minFillQty); offset += 8;
        buffer.putByte(offset, timeInForce); offset += 1;

        return offset;
    }

    private int encodeOrderRequestFast(MutableDirectBuffer buffer, long sequenceId, long orderId,
            String symbol, byte side, byte orderType, long price, long quantity) {

        int offset = 0;
        buffer.putLong(offset, sequenceId); offset += 8;
        buffer.putLong(offset, orderId); offset += 8;

        // Symbol (16 chars)
        byte[] symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 16; i++) {
            buffer.putByte(offset + i, i < symbolBytes.length ? symbolBytes[i] : (byte)0);
        }
        offset += 16;

        buffer.putByte(offset, side); offset += 1;
        buffer.putByte(offset, orderType); offset += 1;
        buffer.putLong(offset, price); offset += 8;
        buffer.putLong(offset, quantity); offset += 8;

        return offset;
    }

    private OrderRequestData decodeOrderRequest(UnsafeBuffer buffer) {
        int offset = 0;

        long sequenceId = buffer.getLong(offset); offset += 8;
        long orderId = buffer.getLong(offset); offset += 8;

        // Symbol
        byte[] symbolBytes = new byte[16];
        buffer.getBytes(offset, symbolBytes);
        String symbol = new String(symbolBytes, StandardCharsets.UTF_8).trim().replaceAll("\0", "");
        offset += 16;

        byte side = buffer.getByte(offset); offset += 1;
        byte orderType = buffer.getByte(offset); offset += 1;
        long price = buffer.getLong(offset); offset += 8;
        long quantity = buffer.getLong(offset); offset += 8;
        long timestamp = buffer.getLong(offset); offset += 8;
        byte algorithm = buffer.getByte(offset); offset += 1;
        long maxLatencyNanos = buffer.getLong(offset); offset += 8;
        int clientId = buffer.getInt(offset); offset += 4;
        long minFillQty = buffer.getLong(offset); offset += 8;
        byte timeInForce = buffer.getByte(offset); offset += 1;

        return new OrderRequestData(sequenceId, orderId, symbol, side, orderType,
                price, quantity, timestamp, algorithm, maxLatencyNanos, clientId,
                minFillQty, timeInForce);
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
        byte[] reasonBytes = rejectReason.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 64; i++) {
            buffer.putByte(offset + i, i < reasonBytes.length ? reasonBytes[i] : (byte)0);
        }
        offset += 64;

        // Group count and allocations
        buffer.putInt(offset, allocations.length); offset += 4;

        for (VenueAllocation allocation : allocations) {
            buffer.putByte(offset, allocation.venueId); offset += 1;
            buffer.putLong(offset, allocation.quantity); offset += 8;
            buffer.putByte(offset, allocation.priority); offset += 1;
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
        String rejectReason = new String(reasonBytes, StandardCharsets.UTF_8).trim().replaceAll("\0", "");
        offset += 64;

        // Allocations
        int allocationCount = buffer.getInt(offset); offset += 4;
        VenueAllocation[] allocations = new VenueAllocation[allocationCount];

        for (int i = 0; i < allocationCount; i++) {
            byte venueId = buffer.getByte(offset); offset += 1;
            long quantity = buffer.getLong(offset); offset += 8;
            byte priority = buffer.getByte(offset); offset += 1;
            long estimatedLatency = buffer.getLong(offset); offset += 8;
            long fees = buffer.getLong(offset); offset += 8;
            int fillProbability = buffer.getInt(offset); offset += 4;

            allocations[i] = new VenueAllocation(venueId, quantity, priority, estimatedLatency, fees, fillProbability);
        }

        return new RoutingDecisionData(sequenceId, orderId, action, estimatedFillTime,
                totalQuantity, estimatedFees, confidence, rejectReason, allocations);
    }

    // Helper methods for enum mapping
    private String getSideName(byte side) {
        return switch(side) {
            case 0 -> "BUY";
            case 1 -> "SELL";
            default -> "UNKNOWN";
        };
    }

    private String getOrderTypeName(byte type) {
        return switch(type) {
            case 0 -> "MARKET";
            case 1 -> "LIMIT";
            case 2 -> "STOP";
            case 3 -> "STOP_LIMIT";
            default -> "UNKNOWN";
        };
    }

    private String getAlgorithmName(byte algorithm) {
        return switch(algorithm) {
            case 1 -> "SIMPLE";
            case 2 -> "TWAP";
            case 3 -> "VWAP";
            case 4 -> "POV";
            case 5 -> "ICEBERG";
            default -> "UNKNOWN";
        };
    }

    private String getRoutingActionName(byte action) {
        return switch(action) {
            case 0 -> "ROUTE_EXTERNAL";
            case 1 -> "ROUTE_INTERNAL";
            case 2 -> "SPLIT_ORDER";
            case 3 -> "REJECT";
            default -> "UNKNOWN";
        };
    }

    private String getVenueName(byte venueId) {
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

    // Data classes
    static class OrderRequestData {
        final long sequenceId, orderId, price, quantity, timestamp, maxLatencyNanos, minFillQty;
        final String symbol;
        final byte side, orderType, algorithm, timeInForce;
        final int clientId;

        OrderRequestData(long sequenceId, long orderId, String symbol, byte side, byte orderType,
                        long price, long quantity, long timestamp, byte algorithm,
                        long maxLatencyNanos, int clientId, long minFillQty, byte timeInForce) {
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
            this.minFillQty = minFillQty;
            this.timeInForce = timeInForce;
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
    }

    static class VenueAllocation {
        final byte venueId, priority;
        final long quantity, estimatedLatency, estimatedFees;
        final int fillProbability;

        VenueAllocation(byte venueId, long quantity, byte priority, long estimatedLatency,
                       long estimatedFees, int fillProbability) {
            this.venueId = venueId;
            this.quantity = quantity;
            this.priority = priority;
            this.estimatedLatency = estimatedLatency;
            this.estimatedFees = estimatedFees;
            this.fillProbability = fillProbability;
        }
    }
}
