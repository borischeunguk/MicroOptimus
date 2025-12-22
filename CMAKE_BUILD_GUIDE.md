# CMake Build System

## Quick Start

### Option 1: Use Build Script (Recommended)
```bash
chmod +x build_all.sh
./build_all.sh
```

### Option 2: Manual Build
```bash
mkdir -p build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
cmake --build . -j8
```

## Build Targets

### Playground Executable
Simple test/experiment program:
```bash
./build/playground
```

### Smart Order Router (Liquidator Module)
If `BUILD_LIQUIDATOR=ON`:
- **Library**: `libsmartorderrouter.dylib` (macOS) / `.so` (Linux)
- **Unit Tests**: `sor_unit_tests` (34 tests with Google Test)
- **Performance Test**: `sor_perf_test` (4M+ orders/sec benchmark)

```bash
# Run unit tests
./build/liquidator/src/main/cpp/sor_unit_tests

# Run performance benchmark
./build/liquidator/src/main/cpp/sor_perf_test
```

## Build Options

Configure with CMake options:

```bash
cmake -DCMAKE_BUILD_TYPE=Release \      # Release or Debug
      -DBUILD_LIQUIDATOR=ON \            # Build Smart Order Router
      -DBUILD_TESTS=ON \                 # Build unit tests
      -DUSE_BOOST=OFF \                  # Optional: Boost libraries
      -DUSE_FOLLY=OFF \                  # Optional: Folly libraries
      ..
```

### Available Options

| Option | Default | Description |
|--------|---------|-------------|
| `BUILD_LIQUIDATOR` | ON | Build Smart Order Router module |
| `BUILD_TESTS` | ON | Build unit tests (requires Google Test) |
| `USE_BOOST` | OFF | Enable Boost libraries support |
| `USE_FOLLY` | OFF | Enable Folly libraries support |

## Project Structure

```
MicroOptimus/
├── CMakeLists.txt              # Root build configuration
├── main.cpp                    # Playground executable
├── build_all.sh               # Build script
├── liquidator/
│   └── src/main/cpp/          # Smart Order Router (C++)
│       ├── CMakeLists.txt     # SOR build configuration
│       ├── sor/               # Modular SOR implementation
│       │   ├── include/       # Headers
│       │   ├── src/           # Implementation
│       │   └── tests/         # Unit tests
│       └── test/              # Integration tests
└── [other modules...]
```

## C++ Standards

- **Root Project**: C++20
- **Liquidator/SOR**: C++17 (for compatibility)

## Compiler Flags

### Release Mode (Default)
- `-O3 -march=native -mtune=native`
- `-ffast-math -funroll-loops`
- Aggressive optimization for performance

### Debug Mode
- `-g -O0 -DDEBUG`
- Full debug symbols, no optimization

## Clean Build

```bash
rm -rf build
./build_all.sh
```

## IDE Integration

### CLion
1. Open project root in CLion
2. CLion will automatically detect CMakeLists.txt
3. Select build configuration (Debug/Release)
4. Build and run targets from IDE

### VSCode
1. Install CMake Tools extension
2. Open project root
3. Select kit (compiler)
4. Configure and build

## Troubleshooting

### CMake Error: Cannot find source file jni_wrapper.cpp

**Error:**
```
CMake Error: Cannot find source file: sor/src/jni/jni_wrapper.cpp
```

**Solution:**
The JNI wrapper file is located at `sor/src/jni_wrapper.cpp` (not in a subdirectory).
This has been fixed in both `CMakeLists.txt` and `CMakeLists_modular.txt`.

If you see this error, the CMakeLists file has the wrong path. It should be:
```cmake
list(APPEND SOR_SOURCES sor/src/jni_wrapper.cpp)  # Correct
# NOT: sor/src/jni/jni_wrapper.cpp
```

### Google Test Not Found
The build system auto-downloads Google Test via FetchContent if not found locally.

### Build Fails
1. Ensure CMake version ≥ 3.20
2. Ensure C++20 compatible compiler
3. Check compiler output for specific errors

### Tests Fail
```bash
cd build/liquidator/src/main/cpp
./sor_unit_tests --gtest_color=yes  # Run with colors
./sor_unit_tests --gtest_filter=VenueScorer*  # Run specific tests
```

## Performance

**Smart Order Router Benchmarks:**
- Avg Latency: 166ns
- P99 Latency: 219ns  
- Throughput: 4.7M orders/sec
- Tests: 34/34 passing (100%)

## Documentation

- [Full Architecture](CLAUDE_MEMORY.md)
- [SOR Implementation](UNIT_TEST_COMPLETION_SUMMARY.md)
- [Test Guide](TESTS_QUICKSTART.md)
- [Aeron Integration](AERON_SOR_ARCHITECTURE.md)

## License

See LICENSE file in project root.

