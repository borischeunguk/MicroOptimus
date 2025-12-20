#!/bin/bash
# Simple manual test: write Java SBE message, read with C++

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHM_FILE="/tmp/test_order.shm"

echo "=== Simple Java -> C++ SBE Test ==="
echo ""

# Step 1: Build C++ reader
echo "Building C++ reader..."
cd "${PROJECT_ROOT}/liquidator/src/main/cpp"
mkdir -p build
cd build
cmake .. -DCMAKE_BUILD_TYPE=Release > /dev/null 2>&1
make sbe_shm_reader_test > /dev/null 2>&1
echo "✅ C++ reader built"
echo ""

# Step 2: Create a simple Java test that writes to a known location
echo "Creating Java writer..."
cat > /tmp/SBEWriter.java << 'EOF'
import com.microoptimus.common.sbe.orders.*;
import org.agrona.concurrent.UnsafeBuffer;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class SBEWriter {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/tmp/test_order.shm";

        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.setLength(1024 * 1024);
            MappedByteBuffer mapped = raf.getChannel().map(
                FileChannel.MapMode.READ_WRITE, 0, 1024 * 1024);

            UnsafeBuffer buffer = new UnsafeBuffer(mapped);
            MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
            OrderRequestEncoder encoder = new OrderRequestEncoder();

            encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
            encoder.sequenceId(12345L)
                   .orderId(99999L)
                   .putSymbol("AAPL".getBytes(), 0)
                   .side(Side.BUY)
                   .orderType(OrderType.LIMIT)
                   .price(150_500_000L)
                   .quantity(25000L)
                   .timestamp(System.nanoTime())
                   .algorithm(Algorithm.VWAP)
                   .maxLatencyNanos(100_000L)
                   .clientId(42)
                   .minFillQty(1000L)
                   .timeInForce((short)1);

            mapped.force();

            System.out.println("✅ Wrote SBE message to: " + path);
            System.out.println("   OrderId: 99999");
            System.out.println("   Symbol: AAPL");
            System.out.println("   Side: BUY");
            System.out.println("   Price: $150.50");
            System.out.println("   Quantity: 25000");
        }
    }
}
EOF

echo "Running Java writer..."
cd "${PROJECT_ROOT}"

# Compile and run the Java writer
javac -cp "common/build/classes/java/main:$(find ~/.gradle/caches -name 'agrona-*.jar' | head -1)" \
    /tmp/SBEWriter.java

java -cp "/tmp:common/build/classes/java/main:$(find ~/.gradle/caches -name 'agrona-*.jar' | head -1)" \
    SBEWriter "${SHM_FILE}"

echo ""

# Step 3: Read with C++
echo "Running C++ reader..."
"${PROJECT_ROOT}/liquidator/src/main/cpp/build/sbe_shm_reader_test" "${SHM_FILE}" single

echo ""
echo "=== Test Complete ==="
echo "This demonstrates zero-copy Java->C++ communication via SBE + shared memory"

