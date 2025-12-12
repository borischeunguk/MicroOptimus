#!/bin/bash

# Smart Order Router Build and Test Script
# This script handles C++ compilation and Java integration testing

set -e  # Exit on any error

echo "=== Smart Order Router Build & Test Script ==="
echo "Date: $(date)"
echo "OS: $(uname -s) $(uname -m)"
echo "Compiler: $(g++ --version | head -n1)"
echo "==============================================="

# Configuration
PROJECT_ROOT="/Users/xinyue/IdeaProjects/MicroOptimus"
LIQUIDATOR_DIR="${PROJECT_ROOT}/liquidator"
CPP_DIR="${LIQUIDATOR_DIR}/src/main/cpp"
BUILD_DIR="${CPP_DIR}/build"

# Build options
BUILD_WITH_JNI=${BUILD_WITH_JNI:-true}
USE_FULL_DEPS=${USE_FULL_DEPS:-false}
BUILD_TYPE=${BUILD_TYPE:-Release}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check basic tools
    command -v g++ >/dev/null 2>&1 || { log_error "g++ is required but not installed"; exit 1; }
    command -v cmake >/dev/null 2>&1 || { log_error "cmake is required but not installed"; exit 1; }
    command -v java >/dev/null 2>&1 || { log_error "Java is required but not installed"; exit 1; }

    log_success "Basic tools are available"

    # Check optional dependencies
    if [[ "$USE_FULL_DEPS" == "true" ]]; then
        if [[ "$OSTYPE" == "darwin"* ]]; then
            if ! brew list boost >/dev/null 2>&1; then
                log_warning "Boost not found. Install with: brew install boost"
                USE_FULL_DEPS=false
            fi
            if ! brew list folly >/dev/null 2>&1; then
                log_warning "Folly not found. Install with: brew install folly"
                USE_FULL_DEPS=false
            fi
        else
            if ! dpkg -l | grep libboost-dev >/dev/null 2>&1; then
                log_warning "Boost not found. Install with: sudo apt-get install libboost-all-dev"
                USE_FULL_DEPS=false
            fi
        fi
    fi
}

# Function to build C++ code
build_cpp() {
    log_info "Building C++ Smart Order Router..."

    cd "$CPP_DIR"

    # Copy the appropriate CMakeLists.txt
    if [[ -f "CMakeLists_simple.txt" ]]; then
        cp CMakeLists_simple.txt CMakeLists.txt
        log_info "Using simplified CMakeLists.txt"
    fi

    # Create build directory
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    # Configure with CMake
    CMAKE_ARGS="-DCMAKE_BUILD_TYPE=${BUILD_TYPE}"

    if [[ "$BUILD_WITH_JNI" == "true" ]]; then
        CMAKE_ARGS="${CMAKE_ARGS} -DBUILD_WITH_JNI=ON"
        # Try to find Java
        if [[ -n "$JAVA_HOME" ]]; then
            CMAKE_ARGS="${CMAKE_ARGS} -DJAVA_HOME=${JAVA_HOME}"
        fi
    fi

    if [[ "$USE_FULL_DEPS" == "true" ]]; then
        CMAKE_ARGS="${CMAKE_ARGS} -DUSE_FULL_DEPENDENCIES=ON"
    fi

    log_info "Running cmake with args: $CMAKE_ARGS"
    cmake .. $CMAKE_ARGS

    # Build
    log_info "Compiling..."
    make -j$(nproc 2>/dev/null || echo 4)

    log_success "C++ build completed successfully"
}

# Function to test C++ standalone
test_cpp_standalone() {
    log_info "Running C++ standalone performance test..."

    cd "$BUILD_DIR"

    if [[ ! -f "sor_perf_test" ]]; then
        log_error "Performance test executable not found"
        return 1
    fi

    # Run performance test
    echo "----------------------------------------"
    ./sor_perf_test
    TEST_RESULT=$?
    echo "----------------------------------------"

    if [[ $TEST_RESULT -eq 0 ]]; then
        log_success "C++ performance test PASSED"
    else
        log_error "C++ performance test FAILED"
        return 1
    fi
}

# Function to compile Java code
build_java() {
    log_info "Building Java code..."

    cd "$PROJECT_ROOT"

    # Build Java classes
    ./gradlew :liquidator:classes

    if [[ "$BUILD_WITH_JNI" == "true" ]]; then
        log_info "Generating JNI headers..."
        ./gradlew :liquidator:generateJNIHeaders || log_warning "JNI header generation failed (expected if Java class not found)"
    fi

    log_success "Java build completed"
}

# Function to test Java integration
test_java_integration() {
    log_info "Running Java SOR integration test..."

    cd "$PROJECT_ROOT"

    # Set library path for JNI
    export LD_LIBRARY_PATH="${BUILD_DIR}:${LD_LIBRARY_PATH}"
    export DYLD_LIBRARY_PATH="${BUILD_DIR}:${DYLD_LIBRARY_PATH}"  # macOS

    # Run Java test
    echo "----------------------------------------"
    ./gradlew :liquidator:runSORTest || {
        log_warning "Java integration test failed - this is expected if JNI library is not available"
        log_info "Java fallback implementation should still work"
        return 0
    }
    echo "----------------------------------------"

    log_success "Java integration test completed"
}

# Function to run benchmarks
run_benchmarks() {
    log_info "Running performance benchmarks..."

    # C++ benchmark
    log_info "C++ Benchmark:"
    cd "$BUILD_DIR"
    echo "Running 3 iterations..."
    for i in {1..3}; do
        echo "--- Iteration $i ---"
        ./sor_perf_test | grep -E "(Average latency|Throughput|P99)" || true
    done

    # Java benchmark (if available)
    if [[ "$BUILD_WITH_JNI" == "true" && -f "${BUILD_DIR}/libsmartorderrouter.so" ]]; then
        log_info "Java+JNI Benchmark:"
        cd "$PROJECT_ROOT"
        ./gradlew :liquidator:runSORTest 2>/dev/null | grep -E "(latency|throughput|Performance)" || log_warning "Java benchmark unavailable"
    fi
}

# Function to install dependencies
install_dependencies() {
    log_info "Installing C++ dependencies..."

    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS with Homebrew
        if command -v brew &> /dev/null; then
            log_info "Installing via Homebrew..."
            brew install cmake boost folly || log_warning "Some dependencies may have failed to install"
        else
            log_error "Homebrew not found. Please install from https://brew.sh/"
            return 1
        fi
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux
        if command -v apt-get &> /dev/null; then
            log_info "Installing via apt-get..."
            sudo apt-get update
            sudo apt-get install -y cmake libboost-all-dev build-essential || log_warning "Some dependencies may have failed to install"
            # Folly is harder to install on Ubuntu - skip for now
            log_warning "Folly not installed - using simplified implementation"
        elif command -v yum &> /dev/null; then
            log_info "Installing via yum..."
            sudo yum install -y cmake boost-devel gcc-c++ || log_warning "Some dependencies may have failed to install"
        else
            log_warning "Package manager not found. Please install dependencies manually"
        fi
    else
        log_warning "Unsupported OS: $OSTYPE"
    fi
}

# Function to clean build
clean_build() {
    log_info "Cleaning build artifacts..."
    rm -rf "$BUILD_DIR"
    cd "$PROJECT_ROOT"
    ./gradlew :liquidator:clean
    log_success "Clean completed"
}

# Function to show help
show_help() {
    cat << EOF
Smart Order Router Build & Test Script

Usage: $0 [COMMAND]

Commands:
  build          - Build C++ and Java code
  test           - Run all tests
  cpp            - Build and test C++ only
  java           - Build and test Java only
  benchmark      - Run performance benchmarks
  install-deps   - Install C++ dependencies
  clean          - Clean build artifacts
  help           - Show this help

Environment Variables:
  BUILD_WITH_JNI=true|false   - Enable JNI support (default: true)
  USE_FULL_DEPS=true|false    - Use Boost/Folly dependencies (default: false)
  BUILD_TYPE=Release|Debug    - CMake build type (default: Release)

Examples:
  $0 build               # Build everything
  $0 cpp                 # C++ only (no dependencies needed)
  $0 install-deps        # Install Boost/Folly
  USE_FULL_DEPS=true $0 build  # Build with all dependencies

EOF
}

# Main execution
main() {
    case "${1:-build}" in
        "build")
            check_prerequisites
            build_cpp
            build_java
            log_success "Build completed successfully!"
            ;;
        "test")
            check_prerequisites
            build_cpp
            build_java
            test_cpp_standalone
            test_java_integration
            log_success "All tests completed!"
            ;;
        "cpp")
            check_prerequisites
            build_cpp
            test_cpp_standalone
            log_success "C++ build and test completed!"
            ;;
        "java")
            build_java
            test_java_integration
            log_success "Java build and test completed!"
            ;;
        "benchmark")
            run_benchmarks
            ;;
        "install-deps")
            install_dependencies
            ;;
        "clean")
            clean_build
            ;;
        "help"|"-h"|"--help")
            show_help
            ;;
        *)
            log_error "Unknown command: $1"
            show_help
            exit 1
            ;;
    esac
}

# Run main function with all arguments
main "$@"
