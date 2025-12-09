package com.microoptimus.common.events.aeron;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * MarketDataPayload - Market data payload stored in shared memory
 *
 * Payload Layout (88 bytes fixed size for simplicity):
 * - symbol:        8 bytes (fixed, padded)
 * - bidPrice:      8 bytes (long)
 * - askPrice:      8 bytes (long)
 * - bidSize:       8 bytes (long)
 * - askSize:       8 bytes (long)
 * - timestamp:     8 bytes (long)
 * - sequenceNum:   8 bytes (long)
 * - bookState:     1 byte
 * - padding:       31 bytes (align to 128 bytes for cache line)
 *
 * Total: 88 bytes (or 128 with padding)
 */
public class MarketDataPayload {

    // Offsets
    private static final int OFFSET_SYMBOL = 0;
    private static final int OFFSET_BID_PRICE = 8;
    private static final int OFFSET_ASK_PRICE = 16;
    private static final int OFFSET_BID_SIZE = 24;
    private static final int OFFSET_ASK_SIZE = 32;
    private static final int OFFSET_TIMESTAMP = 40;
    private static final int OFFSET_SEQUENCE_NUM = 48;
    private static final int OFFSET_BOOK_STATE = 56;

    public static final int PAYLOAD_SIZE = 88; // Without padding
    public static final int SYMBOL_LENGTH = 8;

    // Book states
    public static final byte BOOK_STATE_EMPTY = 0;
    public static final byte BOOK_STATE_ONESIDED = 1;
    public static final byte BOOK_STATE_NORMAL = 2;
    public static final byte BOOK_STATE_LOCKED = 3;
    public static final byte BOOK_STATE_CROSSED = 4;

    /**
     * Encode market data payload into byte array
     */
    public static byte[] encode(
            String symbol,
            long bidPrice,
            long askPrice,
            long bidSize,
            long askSize,
            long timestamp,
            long sequenceNum,
            byte bookState) {

        byte[] bytes = new byte[PAYLOAD_SIZE];
        MutableDirectBuffer buffer = new UnsafeBuffer(bytes);

        encode(buffer, 0, symbol, bidPrice, askPrice, bidSize, askSize,
               timestamp, sequenceNum, bookState);

        return bytes;
    }

    /**
     * Encode market data payload into existing buffer
     */
    public static void encode(
            MutableDirectBuffer buffer,
            int offset,
            String symbol,
            long bidPrice,
            long askPrice,
            long bidSize,
            long askSize,
            long timestamp,
            long sequenceNum,
            byte bookState) {

        // Encode symbol (fixed 8 bytes, padded)
        byte[] symbolBytes = symbol.getBytes(StandardCharsets.US_ASCII);
        int symbolLen = Math.min(symbolBytes.length, SYMBOL_LENGTH);
        buffer.putBytes(offset + OFFSET_SYMBOL, symbolBytes, 0, symbolLen);
        // Pad remaining bytes with zeros
        for (int i = symbolLen; i < SYMBOL_LENGTH; i++) {
            buffer.putByte(offset + OFFSET_SYMBOL + i, (byte) 0);
        }

        // Encode prices and sizes
        buffer.putLong(offset + OFFSET_BID_PRICE, bidPrice);
        buffer.putLong(offset + OFFSET_ASK_PRICE, askPrice);
        buffer.putLong(offset + OFFSET_BID_SIZE, bidSize);
        buffer.putLong(offset + OFFSET_ASK_SIZE, askSize);
        buffer.putLong(offset + OFFSET_TIMESTAMP, timestamp);
        buffer.putLong(offset + OFFSET_SEQUENCE_NUM, sequenceNum);
        buffer.putByte(offset + OFFSET_BOOK_STATE, bookState);
    }

    /**
     * Decode market data payload from buffer
     */
    public static Decoder decode(byte[] bytes) {
        return new Decoder(new UnsafeBuffer(bytes), 0);
    }

    public static Decoder decode(DirectBuffer buffer, int offset) {
        return new Decoder(buffer, offset);
    }

    /**
     * Decoder for reading market data payloads
     */
    public static class Decoder {
        private final DirectBuffer buffer;
        private final int offset;

        public Decoder(DirectBuffer buffer, int offset) {
            this.buffer = buffer;
            this.offset = offset;
        }

        public String symbol() {
            byte[] symbolBytes = new byte[SYMBOL_LENGTH];
            buffer.getBytes(offset + OFFSET_SYMBOL, symbolBytes);
            // Trim trailing zeros
            int len = SYMBOL_LENGTH;
            while (len > 0 && symbolBytes[len - 1] == 0) {
                len--;
            }
            return new String(symbolBytes, 0, len, StandardCharsets.US_ASCII);
        }

        public long bidPrice() {
            return buffer.getLong(offset + OFFSET_BID_PRICE);
        }

        public long askPrice() {
            return buffer.getLong(offset + OFFSET_ASK_PRICE);
        }

        public long bidSize() {
            return buffer.getLong(offset + OFFSET_BID_SIZE);
        }

        public long askSize() {
            return buffer.getLong(offset + OFFSET_ASK_SIZE);
        }

        public long timestamp() {
            return buffer.getLong(offset + OFFSET_TIMESTAMP);
        }

        public long sequenceNum() {
            return buffer.getLong(offset + OFFSET_SEQUENCE_NUM);
        }

        public byte bookState() {
            return buffer.getByte(offset + OFFSET_BOOK_STATE);
        }

        @Override
        public String toString() {
            return String.format("MD[%s bid=%d@%d ask=%d@%d state=%d seq=%d]",
                    symbol(), bidSize(), bidPrice(), askSize(), askPrice(),
                    bookState(), sequenceNum());
        }
    }
}

