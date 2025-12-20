package com.microoptimus.common.sbe;

import com.microoptimus.common.sbe.orders.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that demonstrates Java writing SBE OrderRequest to shared memory
 * that can be read by C++ using the same schema.
 */
public class JavaToSharedMemoryTest {

    private static final int SHARED_MEMORY_SIZE = 1024 * 1024; // 1MB
    private static final int RING_BUFFER_CAPACITY = 64 * 1024; // 64KB for ring buffer

    @Test
    public void testWriteOrderRequestToSharedMemory(@TempDir Path tempDir) throws Exception {
        // Create shared memory file (in production this would be /dev/shm on Linux)
        File shmFile = tempDir.resolve("order_request.shm").toFile();

        // Map the file to memory
        try (RandomAccessFile raf = new RandomAccessFile(shmFile, "rw")) {
            raf.setLength(SHARED_MEMORY_SIZE);

            MappedByteBuffer mappedBuffer = raf.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, SHARED_MEMORY_SIZE);

            // Wrap in Agrona buffer for SBE
            UnsafeBuffer buffer = new UnsafeBuffer(mappedBuffer);

            // Create SBE encoder
            MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
            OrderRequestEncoder encoder = new OrderRequestEncoder();

            // Encode the order request
            int bufferOffset = 0;
            encoder.wrapAndApplyHeader(buffer, bufferOffset, headerEncoder);

            // Set order fields
            encoder.sequenceId(12345L)
                   .orderId(99999L);

            // Symbol must be exactly 16 bytes
            byte[] symbolBytes = new byte[16];
            System.arraycopy("AAPL".getBytes(), 0, symbolBytes, 0, 4);
            encoder.putSymbol(symbolBytes, 0);

            encoder.side(Side.BUY)
                   .orderType(OrderType.LIMIT)
                   .price(150_500_000L) // $150.50
                   .quantity(25000L)
                   .timestamp(System.nanoTime())
                   .algorithm(Algorithm.VWAP)
                   .maxLatencyNanos(100_000L) // 100 microseconds
                   .clientId(42)
                   .minFillQty(1000L)
                   .timeInForce((short)1); // GTC

            // Force buffer to disk
            mappedBuffer.force();

            int encodedLength = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();

            System.out.println("=== Java SBE Encoding Complete ===");
            System.out.println("File: " + shmFile.getAbsolutePath());
            System.out.println("Encoded length: " + encodedLength + " bytes");
            System.out.println("Buffer capacity: " + SHARED_MEMORY_SIZE + " bytes");

            // Verify we can decode it back in Java
            verifyJavaDecoding(buffer, bufferOffset);

            // Print instructions for C++ reader
            printCppReadInstructions(shmFile.getAbsolutePath(), encodedLength);
        }
    }

    @Test
    public void testRingBufferPattern(@TempDir Path tempDir) throws Exception {
        // Simulate a ring buffer with multiple orders
        File shmFile = tempDir.resolve("order_ring_buffer.shm").toFile();

        try (RandomAccessFile raf = new RandomAccessFile(shmFile, "rw")) {
            raf.setLength(RING_BUFFER_CAPACITY);

            MappedByteBuffer mappedBuffer = raf.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, RING_BUFFER_CAPACITY);

            UnsafeBuffer buffer = new UnsafeBuffer(mappedBuffer);

            // Ring buffer header: [writePosition(8) | readPosition(8) | sequence(8) | padding]
            int headerSize = 32;
            long writePosition = 0;
            long readPosition = 0;
            long sequence = 0;

            buffer.putLong(0, writePosition);
            buffer.putLong(8, readPosition);
            buffer.putLong(16, sequence);

            // Write 3 orders to the ring buffer
            MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
            OrderRequestEncoder encoder = new OrderRequestEncoder();

            String[] symbols = {"AAPL", "MSFT", "GOOGL"};
            long[] quantities = {10000L, 15000L, 20000L};

            int currentOffset = headerSize;

            for (int i = 0; i < symbols.length; i++) {
                encoder.wrapAndApplyHeader(buffer, currentOffset, headerEncoder);

                encoder.sequenceId(sequence++)
                       .orderId(1000L + i);

                // Symbol must be exactly 16 bytes
                byte[] symbolBytes = new byte[16];
                byte[] srcBytes = symbols[i].getBytes();
                System.arraycopy(srcBytes, 0, symbolBytes, 0, srcBytes.length);
                encoder.putSymbol(symbolBytes, 0);

                encoder.side(i % 2 == 0 ? Side.BUY : Side.SELL)
                       .orderType(OrderType.LIMIT)
                       .price(100_000_000L + (i * 5_000_000L))
                       .quantity(quantities[i])
                       .timestamp(System.nanoTime())
                       .algorithm(Algorithm.SIMPLE)
                       .maxLatencyNanos(50_000L)
                       .clientId(i + 1)
                       .minFillQty(100L)
                       .timeInForce((short)1);

                int messageLength = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
                writePosition += messageLength;
                currentOffset += messageLength;

                System.out.println("Wrote order " + i + ": " + symbols[i] +
                                 " at offset " + (currentOffset - messageLength));
            }

            // Update write position
            buffer.putLong(0, writePosition);
            buffer.putLong(16, sequence);

            mappedBuffer.force();

            System.out.println("\n=== Ring Buffer Layout ===");
            System.out.println("File: " + shmFile.getAbsolutePath());
            System.out.println("Header size: " + headerSize);
            System.out.println("Write position: " + writePosition);
            System.out.println("Orders written: " + symbols.length);
            System.out.println("Total sequence: " + sequence);

            // Verify reading back
            verifyRingBufferReading(buffer, headerSize, (int)writePosition);

            printCppRingBufferInstructions(shmFile.getAbsolutePath());
        }
    }

    private void verifyJavaDecoding(UnsafeBuffer buffer, int offset) {
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        OrderRequestDecoder decoder = new OrderRequestDecoder();

        headerDecoder.wrap(buffer, offset);
        decoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        System.out.println("\n=== Java Decoding Verification ===");
        System.out.println("SequenceId: " + decoder.sequenceId());
        System.out.println("OrderId: " + decoder.orderId());

        byte[] symbolBytes = new byte[16];
        decoder.getSymbol(symbolBytes, 0);
        String symbol = new String(symbolBytes).trim();
        System.out.println("Symbol: " + symbol);

        System.out.println("Side: " + decoder.side());
        System.out.println("OrderType: " + decoder.orderType());
        System.out.println("Price: $" + (decoder.price() / 1_000_000.0));
        System.out.println("Quantity: " + decoder.quantity());
        System.out.println("Algorithm: " + decoder.algorithm());

        assertEquals(12345L, decoder.sequenceId());
        assertEquals(99999L, decoder.orderId());
        assertEquals("AAPL", symbol);
        assertEquals(Side.BUY, decoder.side());
        assertEquals(150_500_000L, decoder.price());
        assertEquals(25000L, decoder.quantity());
    }

    private void verifyRingBufferReading(UnsafeBuffer buffer, int headerSize, int writePosition) {
        long storedWritePos = buffer.getLong(0);
        long storedReadPos = buffer.getLong(8);
        long storedSequence = buffer.getLong(16);

        System.out.println("\n=== Ring Buffer Header ===");
        System.out.println("Write Position: " + storedWritePos);
        System.out.println("Read Position: " + storedReadPos);
        System.out.println("Sequence: " + storedSequence);

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        OrderRequestDecoder decoder = new OrderRequestDecoder();

        int currentOffset = headerSize;
        int orderCount = 0;

        System.out.println("\n=== Decoding Orders ===");
        while (currentOffset < headerSize + writePosition) {
            headerDecoder.wrap(buffer, currentOffset);
            decoder.wrapAndApplyHeader(buffer, currentOffset, headerDecoder);

            byte[] symbolBytes = new byte[16];
            decoder.getSymbol(symbolBytes, 0);
            String symbol = new String(symbolBytes).trim();

            System.out.println("Order " + orderCount + ": " + symbol +
                             " | Side: " + decoder.side() +
                             " | Qty: " + decoder.quantity() +
                             " | Seq: " + decoder.sequenceId());

            currentOffset += MessageHeaderEncoder.ENCODED_LENGTH + decoder.encodedLength();
            orderCount++;
        }

        assertEquals(3, orderCount);
    }

    private void printCppReadInstructions(String filePath, int encodedLength) {
        System.out.println("\n=== C++ Reading Instructions ===");
        System.out.println("To read this from C++:");
        System.out.println("1. Map file: " + filePath);
        System.out.println("2. Message length: " + encodedLength + " bytes");
        System.out.println("3. Use SBE C++ generated code from OrderRequestMessage.xml");
        System.out.println("4. Example code:");
        System.out.println("   OrderRequest decoder;");
        System.out.println("   decoder.wrapForDecode(buffer, offset, blockLength, version);");
        System.out.println("   uint64_t orderId = decoder.orderId();");
    }

    private void printCppRingBufferInstructions(String filePath) {
        System.out.println("\n=== C++ Ring Buffer Reading Instructions ===");
        System.out.println("1. Map file: " + filePath);
        System.out.println("2. Ring buffer structure:");
        System.out.println("   Offset 0-7:   writePosition (uint64)");
        System.out.println("   Offset 8-15:  readPosition (uint64)");
        System.out.println("   Offset 16-23: sequence (uint64)");
        System.out.println("   Offset 32+:   SBE messages");
        System.out.println("3. Read messages from offset 32 until writePosition");
    }
}

