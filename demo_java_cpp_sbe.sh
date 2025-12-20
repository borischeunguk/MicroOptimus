#!/bin/bash
# Simple demonstration: Java writes SBE to /tmp, C++ reads it back

set -e

PROJECT_ROOT="/Users/xinyue/IdeaProjects/MicroOptimus"
SHM_FILE="/tmp/order_request_demo.shm"

echo "=========================================="
echo "Java → C++ SBE Demo"
echo "=========================================="
echo ""

# Step 1: Clean old file
rm -f "${SHM_FILE}"

# Step 2: Compile Java
echo "Step 1: Compiling Java SBE writer..."
cd "${PROJECT_ROOT}"
./gradlew :common:compileTestJava -q

# Step 3: Run Java writer
echo ""
echo "Step 2: Running Java SBE encoder..."
CLASSPATH="common/build/classes/java/test:common/build/classes/java/main:$(./gradlew :common:printClasspath -q)"
java -cp "${CLASSPATH}" com.microoptimus.common.sbe.SimpleSBEWriter "${SHM_FILE}"

# Step 4: Run C++ reader
echo ""
echo "Step 3: Running C++ SBE decoder..."
"${PROJECT_ROOT}/liquidator/src/main/cpp/build/sbe_shm_reader_test" "${SHM_FILE}" single

echo ""
echo "=========================================="
echo "✅ Demo Complete!"
echo "=========================================="
echo ""
echo "What just happened:"
echo "  1. Java wrote an OrderRequest to shared memory using SBE"
echo "  2. C++ read the same binary data with zero copying"
echo "  3. Both sides used the same schema (OrderRequestMessage.xml)"
echo "  4. No JNI, no serialization, no overhead!"
echo ""
echo "This is the foundation for sub-microsecond Java↔C++ communication."

