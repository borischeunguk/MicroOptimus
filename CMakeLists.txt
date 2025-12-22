cmake_minimum_required(VERSION 3.20)
project(MicroOptimus LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

message(STATUS "=== MicroOptimus Project Build Configuration ===")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")

# Build options
option(BUILD_LIQUIDATOR "Build Liquidator module (Smart Order Router)" ON)
option(BUILD_TESTS "Build unit tests" ON)
option(USE_BOOST "Use Boost libraries" OFF)
option(USE_FOLLY "Use Folly libraries" OFF)

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Wpedantic")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -march=native -mtune=native")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0 -DDEBUG")

# Add subdirectories
if(BUILD_LIQUIDATOR)
    message(STATUS "Building Liquidator module (Smart Order Router)")
    add_subdirectory(liquidator/src/main/cpp)
endif()

# Playground executable for testing and experiments
add_executable(playground
    main.cpp
)

message(STATUS "================================================")

