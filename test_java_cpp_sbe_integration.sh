#!/bin/bash
# Run Java->C++ SBE shared memory integration test

set -e

echo "==================================================================="
echo "Java -> C++ SBE Shared Memory Integration Test"
echo "==================================================================="
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIQUIDATOR_CPP_DIR="${PROJECT_ROOT}/liquidator/src/main/cpp"
BUILD_DIR="${LIQUIDATOR_CPP_DIR}/build"
SHM_DIR="/tmp/microoptimus_sbe_test"

# Step 1: Build C++ reader
echo -e "${BLUE}Step 1: Building C++ SBE reader${NC}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

cmake .. -DCMAKE_BUILD_TYPE=Release
make sbe_shm_reader_test

if [ ! -f "${BUILD_DIR}/sbe_shm_reader_test" ]; then
    echo -e "${RED}❌ Failed to build C++ reader${NC}"
    exit 1
fi

echo -e "${GREEN}✅ C++ reader built successfully${NC}"
echo ""

# Step 2: Ensure shared memory directory exists
mkdir -p "${SHM_DIR}"
echo -e "${BLUE}Step 2: Created shared memory directory: ${SHM_DIR}${NC}"
echo ""

# Step 3: Run Java test to write SBE messages to shared memory
echo -e "${BLUE}Step 3: Running Java test (writes to shared memory)${NC}"
cd "${PROJECT_ROOT}"

./gradlew :common:test --tests "com.microoptimus.common.sbe.JavaToSharedMemoryTest" --info

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Java test failed${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Java test completed${NC}"
echo ""

# Step 4: Find the generated shared memory files
echo -e "${BLUE}Step 4: Locating shared memory files${NC}"

# Java test creates temp files, we need to find them
# Look in common/build/test-results for the temp directory location
TEMP_DIRS=$(find "${PROJECT_ROOT}/common/build" -type d -name "junit*" 2>/dev/null || true)

SHM_SINGLE=""
SHM_RING=""

for dir in ${TEMP_DIRS}; do
    if [ -f "${dir}/order_request.shm" ]; then
        SHM_SINGLE="${dir}/order_request.shm"
    fi
    if [ -f "${dir}/order_ring_buffer.shm" ]; then
        SHM_RING="${dir}/order_ring_buffer.shm"
    fi
done

# Fallback: check build directory
if [ -z "${SHM_SINGLE}" ]; then
    SHM_SINGLE=$(find "${PROJECT_ROOT}/common/build" -name "order_request.shm" 2>/dev/null | head -1 || true)
fi

if [ -z "${SHM_RING}" ]; then
    SHM_RING=$(find "${PROJECT_ROOT}/common/build" -name "order_ring_buffer.shm" 2>/dev/null | head -1 || true)
fi

if [ -z "${SHM_SINGLE}" ] && [ -z "${SHM_RING}" ]; then
    echo -e "${RED}❌ Could not find shared memory files${NC}"
    echo "Expected files:"
    echo "  - order_request.shm"
    echo "  - order_ring_buffer.shm"
    echo ""
    echo "Searching in: ${PROJECT_ROOT}/common/build"
    find "${PROJECT_ROOT}/common/build" -name "*.shm" 2>/dev/null || true
    exit 1
fi

echo -e "${GREEN}Found shared memory files:${NC}"
[ -n "${SHM_SINGLE}" ] && echo "  Single message: ${SHM_SINGLE}"
[ -n "${SHM_RING}" ] && echo "  Ring buffer:    ${SHM_RING}"
echo ""

# Step 5: Run C++ reader to decode the messages
echo -e "${BLUE}Step 5: Running C++ reader${NC}"
echo ""

if [ -n "${SHM_SINGLE}" ]; then
    echo -e "${BLUE}--- Reading single message ---${NC}"
    "${BUILD_DIR}/sbe_shm_reader_test" "${SHM_SINGLE}" single
    echo ""
fi

if [ -n "${SHM_RING}" ]; then
    echo -e "${BLUE}--- Reading ring buffer ---${NC}"
    "${BUILD_DIR}/sbe_shm_reader_test" "${SHM_RING}" ring
    echo ""
fi

echo "==================================================================="
echo -e "${GREEN}✅ Integration test completed successfully!${NC}"
echo "==================================================================="
echo ""
echo "Summary:"
echo "  1. Java wrote SBE-encoded OrderRequest to shared memory"
echo "  2. C++ read the same binary format with zero copying"
echo "  3. No JNI, no serialization overhead"
echo "  4. Both sides used the same SBE schema (OrderRequestMessage.xml)"
echo ""
echo "This demonstrates the foundation for:"
echo "  - Java OSM -> C++ SOR via shared memory"
echo "  - Global sequencer + zero-copy architecture"
echo "  - Sub-microsecond latency communication"

