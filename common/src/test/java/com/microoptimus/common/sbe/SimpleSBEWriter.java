package com.microoptimus.common.sbe;

import com.microoptimus.common.sbe.orders.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Simple writer that creates an SBE OrderRequest in /tmp for C++ demo
 */
public class SimpleSBEWriter {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/tmp/order_request_demo.shm";

        System.out.println("=== Java SBE Writer ===");
        System.out.println("Writing to: " + path);
        System.out.println();

        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.setLength(1024 * 1024); // 1MB

            MappedByteBuffer mapped = raf.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, 1024 * 1024);

            UnsafeBuffer buffer = new UnsafeBuffer(mapped);
            MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
            OrderRequestEncoder encoder = new OrderRequestEncoder();

            encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

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

            mapped.force();

            int encodedLength = MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();

            System.out.println("✅ Successfully wrote SBE message:");
            System.out.println("   File: " + path);
            System.out.println("   Size: " + encodedLength + " bytes");
            System.out.println("   OrderId: 99999");
            System.out.println("   Symbol: AAPL");
            System.out.println("   Side: BUY");
            System.out.println("   Price: $150.50");
            System.out.println("   Quantity: 25,000");
            System.out.println();
            System.out.println("Now run C++ reader:");
            System.out.println("   ./liquidator/src/main/cpp/build/sbe_shm_reader_test " + path + " single");
        }
    }
}

