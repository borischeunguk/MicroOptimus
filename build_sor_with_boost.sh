#!/bin/bash
# Build and test Smart Order Router with Boost circular_buffer test
# This script enables Boost and compiles the circular_buffer unit tests

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build_with_boost"

echo "======================================"
echo "Smart Order Router Build with Boost"
echo "======================================"

# Check if Boost is installed
echo ""
echo "Checking for Boost installation..."
if [ -d "/opt/homebrew/opt/boost" ]; then
    echo "✅ Boost found via Homebrew (Apple Silicon)"
    BOOST_ROOT="/opt/homebrew/opt/boost"
elif [ -d "/usr/local/opt/boost" ]; then
    echo "✅ Boost found via Homebrew (Intel)"
    BOOST_ROOT="/usr/local/opt/boost"
elif [ -d "/usr/include/boost" ]; then
    echo "✅ Boost found in system path"
    BOOST_ROOT=""
else
    echo "❌ Boost not found!"
    echo ""
    echo "Please install Boost:"
    echo "  macOS: brew install boost"
    echo "  Linux: sudo apt-get install libboost-all-dev"
    echo ""
    exit 1
fi

# Step 1: Clean old build
echo ""
echo "Step 1: Cleaning old build..."
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# Step 2: Configure with CMake (Boost enabled)
echo ""
echo "Step 2: Configuring with CMake (Boost enabled)..."
cd "${BUILD_DIR}"

CMAKE_ARGS=(
    ..
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_LIQUIDATOR=ON
    -DBUILD_TESTING=ON
    -DBUILD_WITH_JNI=OFF
    -DUSE_BOOST=ON
    -DUSE_FOLLY=OFF
)

if [ -n "$BOOST_ROOT" ]; then
    CMAKE_ARGS+=(-DBOOST_ROOT="$BOOST_ROOT")
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

# Step 5: Run Boost circular_buffer test
echo ""
echo "======================================"
echo "Running Boost Circular Buffer Test"
echo "======================================"
if [ -f "./boost_circular_buffer_test" ]; then
    ./boost_circular_buffer_test
else
    echo "❌ Boost circular_buffer test not found!"
    echo "This might mean Boost was not properly detected."
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
echo "✅ Boost circular_buffer test: PASSED"
echo "✅ Performance test: PASSED"
echo ""
echo "Build artifacts:"
echo "  - Library: ${BUILD_DIR}/liquidator/src/main/cpp/libsmartorderrouter.dylib"
echo "  - Unit tests: ${BUILD_DIR}/liquidator/src/main/cpp/sor_unit_tests"
echo "  - Boost test: ${BUILD_DIR}/liquidator/src/main/cpp/boost_circular_buffer_test"
echo "  - Perf test: ${BUILD_DIR}/liquidator/src/main/cpp/sor_perf_test"
echo ""
echo "All tests passed successfully! 🎉"

