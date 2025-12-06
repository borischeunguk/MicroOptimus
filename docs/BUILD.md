# Building MicroOptimus

This guide explains how to build and run the MicroOptimus trading system.

## Prerequisites

### C++ Components
- CMake 3.10 or higher
- C++17 compatible compiler (GCC 7+, Clang 5+, MSVC 2017+)
- pthreads library

### Java Components
- JDK 11 or higher
- Maven 3.6 or higher

### Python Components
- Python 3.7 or higher
- pip

## Building C++ Components

### Linux/macOS

```bash
# Create build directory
mkdir -p build
cd build

# Configure with CMake
cmake ..

# Build
make -j$(nproc)

# Run demo
./microoptimus
```

### With specific build type

```bash
# Debug build
cmake -DCMAKE_BUILD_TYPE=Debug ..
make -j$(nproc)

# Release build (optimized)
cmake -DCMAKE_BUILD_TYPE=Release ..
make -j$(nproc)
```

### Windows

```bash
# Create build directory
mkdir build
cd build

# Configure for Visual Studio
cmake -G "Visual Studio 16 2019" ..

# Build
cmake --build . --config Release

# Run demo
.\Release\microoptimus.exe
```

## Building Java Components

```bash
# Navigate to Java directory
cd java

# Build with Maven
mvn clean package

# Run demo
java -jar target/microoptimus-java-1.0.0.jar
```

### Development mode

```bash
# Compile only
mvn compile

# Run tests
mvn test

# Run main class directly
mvn exec:java -Dexec.mainClass="com.microoptimus.Main"
```

## Running Python Components

```bash
# Navigate to Python directory
cd python

# Install dependencies (none required for core functionality)
pip install -r requirements.txt

# Run market maker demo
python demo_market_maker.py

# Or make it executable and run
chmod +x demo_market_maker.py
./demo_market_maker.py
```

## Complete System Build

To build all components:

```bash
# From repository root

# 1. Build C++
mkdir -p build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
make -j$(nproc)
cd ..

# 2. Build Java
cd java
mvn clean package
cd ..

# 3. Setup Python
cd python
pip install -r requirements.txt
cd ..
```

## Running Demos

### C++ Orderbook and Matching Demo
```bash
./build/microoptimus
```

Expected output:
- Orderbook creation
- Order matching scenarios
- Trade execution
- Matching statistics

### Java Gateway Demo
```bash
java -jar java/target/microoptimus-java-1.0.0.jar
```

Expected output:
- Connection establishment
- Market data simulation
- Order submission
- Order fills

### Python Market Maker Demo
```bash
cd python
python demo_market_maker.py
```

Expected output:
- Quote generation
- Position building
- P&L tracking
- Risk management

## Installation

### C++ Components
```bash
cd build
sudo make install
```

This installs:
- `microoptimus` binary to `/usr/local/bin`
- Libraries to `/usr/local/lib`
- Headers to `/usr/local/include/microoptimus`

### Java Components
```bash
cd java
mvn install
```

This installs the JAR to your local Maven repository.

### Python Components
```bash
cd python
pip install -e .
```

This installs the market maker module in development mode.

## Docker Build (Optional)

Create a Dockerfile:

```dockerfile
FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    openjdk-11-jdk \
    maven \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# Copy source
COPY . /app
WORKDIR /app

# Build C++
RUN mkdir -p build && cd build && \
    cmake -DCMAKE_BUILD_TYPE=Release .. && \
    make -j$(nproc)

# Build Java
RUN cd java && mvn clean package

# Setup Python
RUN cd python && pip3 install -r requirements.txt

CMD ["/bin/bash"]
```

Build and run:
```bash
docker build -t microoptimus .
docker run -it microoptimus
```

## Troubleshooting

### C++ Build Issues

**Error: CMake version too old**
```bash
# Install newer CMake
pip install cmake --upgrade
```

**Error: Thread library not found**
```bash
# Install pthread on Ubuntu/Debian
sudo apt-get install libpthread-stubs0-dev
```

### Java Build Issues

**Error: JAVA_HOME not set**
```bash
export JAVA_HOME=/path/to/jdk
```

**Error: Maven not found**
```bash
# Install Maven
sudo apt-get install maven  # Ubuntu/Debian
brew install maven           # macOS
```

### Python Issues

**Error: Module not found**
```bash
# Ensure you're in the python directory
cd python
# Or add to PYTHONPATH
export PYTHONPATH=$PYTHONPATH:/path/to/MicroOptimus/python
```

## Performance Tuning

### C++ Optimizations

1. **Use Release Build**
   ```bash
   cmake -DCMAKE_BUILD_TYPE=Release ..
   ```

2. **Enable LTO (Link Time Optimization)**
   ```bash
   cmake -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON ..
   ```

3. **CPU-specific optimization**
   ```bash
   cmake -DCMAKE_CXX_FLAGS="-march=native" ..
   ```

### Java Optimizations

1. **Increase heap size**
   ```bash
   java -Xmx4g -jar microoptimus-java-1.0.0.jar
   ```

2. **Use G1GC**
   ```bash
   java -XX:+UseG1GC -jar microoptimus-java-1.0.0.jar
   ```

### Python Optimizations

1. **Use PyPy** (for CPU-intensive operations)
   ```bash
   pypy3 demo_market_maker.py
   ```

2. **Profile performance**
   ```bash
   python -m cProfile demo_market_maker.py
   ```
