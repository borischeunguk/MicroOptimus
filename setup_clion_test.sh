#!/bin/bash
# Quick setup script for running Boost circular_buffer test in CLion
# This configures the project for CLion GUI testing

echo "=========================================="
echo "CLion Test Setup for Boost circular_buffer"
echo "=========================================="
echo ""

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Step 1: Checking Boost installation..."
if command -v brew &> /dev/null; then
    if brew list boost &> /dev/null; then
        echo "✅ Boost is installed via Homebrew"
        BOOST_ROOT=$(brew --prefix boost)
        echo "   Location: $BOOST_ROOT"
    else
        echo "❌ Boost not found"
        echo "   Install with: brew install boost"
        exit 1
    fi
elif [ -d "/usr/include/boost" ]; then
    echo "✅ Boost found in system path"
else
    echo "❌ Boost not found"
    echo "   Install with: sudo apt-get install libboost-all-dev"
    exit 1
fi

echo ""
echo "Step 2: Creating CLion-friendly build directory..."
BUILD_DIR="${PROJECT_ROOT}/cmake-build-debug-boost"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

echo ""
echo "Step 3: Configuring CMake with Boost enabled..."
cmake "${PROJECT_ROOT}" \
    -DCMAKE_BUILD_TYPE=Debug \
    -DBUILD_LIQUIDATOR=ON \
    -DBUILD_TESTING=ON \
    -DBUILD_WITH_JNI=OFF \
    -DUSE_BOOST=ON \
    -DUSE_FOLLY=OFF \
    -G "Unix Makefiles"

if [ $? -eq 0 ]; then
    echo "✅ CMake configuration successful"
else
    echo "❌ CMake configuration failed"
    exit 1
fi

echo ""
echo "Step 4: Building boost_circular_buffer_test..."
cmake --build . --target boost_circular_buffer_test

if [ $? -eq 0 ]; then
    echo "✅ Build successful"
else
    echo "❌ Build failed"
    exit 1
fi

echo ""
echo "=========================================="
echo "Setup Complete! ✅"
echo "=========================================="
echo ""
echo "🎯 Next Steps in CLion:"
echo ""
echo "1. Open CLion and go to: File → Reload CMake Project"
echo ""
echo "2. In CMake settings (File → Settings → Build,Execution,Deployment → CMake):"
echo "   Add to 'CMake options':"
echo "   -DUSE_BOOST=ON -DBUILD_TESTING=ON"
echo ""
echo "3. Run the test:"
echo "   - Click run dropdown (top right)"
echo "   - Select 'boost_circular_buffer_test'"
echo "   - Click green ▶️ button"
echo ""
echo "📝 Or run from terminal:"
echo "   cd ${BUILD_DIR}/liquidator/src/main/cpp"
echo "   ./boost_circular_buffer_test"
echo ""
echo "📖 Full guide available in: CLION_TEST_GUIDE.md"
echo ""

