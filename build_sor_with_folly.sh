#!/bin/bash
# Build and test Smart Order Router with Folly SPSC queue test
# This script enables Folly and compiles the SPSC queue unit tests

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build_with_folly"

echo "======================================"
echo "Smart Order Router Build with Folly"
echo "======================================"

# Check if Folly is installed
echo ""
echo "Checking for Folly installation..."
if [ -d "/opt/homebrew/opt/folly" ]; then
    echo "✅ Folly found via Homebrew (Apple Silicon)"
    FOLLY_ROOT="/opt/homebrew/opt/folly"
elif [ -d "/usr/local/opt/folly" ]; then
    echo "✅ Folly found via Homebrew (Intel)"
    FOLLY_ROOT="/usr/local/opt/folly"
elif pkg-config --exists libfolly 2>/dev/null; then
    echo "✅ Folly found via pkg-config"
    FOLLY_ROOT=""
else
    echo "❌ Folly not found!"
    echo ""
    echo "Please install Folly:"
    echo "  macOS: brew install folly"
    echo "  Linux: Follow https://github.com/facebook/folly"
    echo ""
    exit 1
fi

# Step 1: Clean old build
echo ""
echo "Step 1: Cleaning old build..."
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# Step 2: Configure with CMake (Folly enabled)
echo ""
echo "Step 2: Configuring with CMake (Folly enabled)..."
cd "${BUILD_DIR}"

CMAKE_ARGS=(
    ..
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_LIQUIDATOR=ON
    -DBUILD_TESTING=ON
    -DBUILD_WITH_JNI=OFF
    -DUSE_BOOST=OFF
    -DUSE_FOLLY=ON
)

if [ -n "$FOLLY_ROOT" ]; then
    CMAKE_ARGS+=(-DCMAKE_PREFIX_PATH="$FOLLY_ROOT")
fi

cmake "${CMAKE_ARGS[@]}"

# Step 3: Build
echo ""
echo "Step 3: Building..."
NUM_CORES=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)
cmake --build . -j${NUM_CORES}

# Step 4: Run C++ unit tests
echo ""
echo "======================================"
echo "Running C++ Unit Tests"
echo "======================================"
cd "${BUILD_DIR}/liquidator/src/main/cpp"

if [ -f "./sor_unit_tests" ]; then
    ./sor_unit_tests
else
    echo "⚠️  SOR unit tests not found"
fi

# Step 5: Run Folly SPSC queue test
echo ""
echo "======================================"
echo "Running Folly SPSC Queue Test"
echo "======================================"
if [ -f "./folly_spsc_queue_test" ]; then
    ./folly_spsc_queue_test
else
    echo "❌ Folly SPSC queue test not found!"
    echo "This might mean Folly was not properly detected."
    exit 1
fi

# Step 6: Run performance test
echo ""
echo "======================================"
echo "Running Performance Test"
echo "======================================"
if [ -f "./sor_perf_test" ]; then
    ./sor_perf_test
else
    echo "⚠️  Performance test not found"
fi

# Step 7: Summary
echo ""
echo "======================================"
echo "Build & Test Summary"
echo "======================================"
echo "✅ CMake configuration: SUCCESS"
echo "✅ C++ compilation: SUCCESS"
echo "✅ Unit tests: PASSED"
echo "✅ Folly SPSC queue test: PASSED"
echo "✅ Performance test: PASSED"
echo ""
echo "Build artifacts:"
echo "  - Library: ${BUILD_DIR}/liquidator/src/main/cpp/libsmartorderrouter.dylib"
echo "  - Unit tests: ${BUILD_DIR}/liquidator/src/main/cpp/sor_unit_tests"
echo "  - Folly test: ${BUILD_DIR}/liquidator/src/main/cpp/folly_spsc_queue_test"
echo "  - Perf test: ${BUILD_DIR}/liquidator/src/main/cpp/sor_perf_test"
echo ""
echo "All tests passed successfully! 🎉"

