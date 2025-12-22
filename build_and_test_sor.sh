#!/bin/bash
# Build and test Smart Order Router (Liquidator module)
# This script compiles the C++ SOR and runs all tests

set -e  # Exit on error

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

echo "======================================"
echo "Smart Order Router Build & Test"
echo "======================================"

# Step 1: Clean old build
echo ""
echo "Step 1: Cleaning old build..."
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# Step 2: Configure with CMake
echo ""
echo "Step 2: Configuring with CMake..."
cd "${BUILD_DIR}"
cmake .. \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_LIQUIDATOR=ON \
  -DBUILD_TESTING=ON \
  -DBUILD_WITH_JNI=OFF \
  -DUSE_BOOST=OFF \
  -DUSE_FOLLY=OFF

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
./sor_unit_tests

# Step 5: Run performance test
echo ""
echo "======================================"
echo "Running Performance Test"
echo "======================================"
./sor_perf_test

# Step 6: Summary
echo ""
echo "======================================"
echo "Build & Test Summary"
echo "======================================"
echo "✅ CMake configuration: SUCCESS"
echo "✅ C++ compilation: SUCCESS"
echo "✅ Unit tests: PASSED"
echo "✅ Performance test: PASSED"
echo ""
echo "Build artifacts:"
echo "  - Library: ${BUILD_DIR}/liquidator/src/main/cpp/libsmartorderrouter.dylib"
echo "  - Unit tests: ${BUILD_DIR}/liquidator/src/main/cpp/sor_unit_tests"
echo "  - Perf test: ${BUILD_DIR}/liquidator/src/main/cpp/sor_perf_test"
echo ""
echo "All tests passed successfully! 🎉"

