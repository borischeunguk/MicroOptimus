#!/bin/bash
# Build script for MicroOptimus project

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR="${SCRIPT_DIR}/build"

echo "=== Building MicroOptimus Project ==="
echo "Build directory: ${BUILD_DIR}"
echo ""

# Create build directory
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Configure with CMake
echo "=== Configuring CMake ==="
cmake -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_LIQUIDATOR=ON \
      -DBUILD_TESTS=ON \
      -DUSE_BOOST=OFF \
      -DUSE_FOLLY=OFF \
      ..

# Build
echo ""
echo "=== Building ==="
NUM_CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
cmake --build . --config Release -j${NUM_CORES}

# Show built executables
echo ""
echo "=== Build Complete ==="
echo "Built executables:"
ls -lh playground 2>/dev/null || echo "  (playground not found)"
cd liquidator/src/main/cpp
ls -lh sor_unit_tests 2>/dev/null || echo "  (sor_unit_tests not found)"
ls -lh sor_perf_test 2>/dev/null || echo "  (sor_perf_test not found)"
ls -lh libsmartorderrouter* 2>/dev/null || echo "  (libsmartorderrouter not found)"

echo ""
echo "To run:"
echo "  ./build/playground"
echo "  ./build/liquidator/src/main/cpp/sor_unit_tests"
echo "  ./build/liquidator/src/main/cpp/sor_perf_test"

