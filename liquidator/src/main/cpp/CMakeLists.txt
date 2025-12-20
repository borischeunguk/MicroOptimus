cmake_minimum_required(VERSION 3.16)
project(SmartOrderRouter CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimize for performance
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -march=native -mtune=native")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra")

# Debug flags
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0 -DDEBUG")

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")

# Option to build with JNI support
option(BUILD_WITH_JNI "Build with JNI support for Java integration" OFF)

# Option to use full dependencies (Boost/Folly)
option(USE_FULL_DEPENDENCIES "Use Boost and Folly (requires installation)" OFF)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Find JNI if requested
if(BUILD_WITH_JNI)
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
    add_definitions(-DWITH_JNI)
    message(STATUS "Building with JNI support")
endif()

# Find optional dependencies
if(USE_FULL_DEPENDENCIES)
    find_package(Boost COMPONENTS system)
    find_package(folly CONFIG)

    if(Boost_FOUND)
        include_directories(${Boost_INCLUDE_DIRS})
        add_definitions(-DWITH_BOOST)
        message(STATUS "Found Boost: ${Boost_VERSION}")
    endif()

    if(folly_FOUND)
        add_definitions(-DWITH_FOLLY)
        message(STATUS "Found Folly")
    endif()
endif()

# Source files - use simplified version by default
set(SOR_SOURCES
    sor/smart_order_router_simple.cpp
)

if(USE_FULL_DEPENDENCIES AND Boost_FOUND AND folly_FOUND)
    set(SOR_SOURCES
        sor/smart_order_router.cpp
    )
    message(STATUS "Using full-featured SOR implementation")
else()
    message(STATUS "Using simplified SOR implementation (no external deps)")
endif()

# Performance test executable (standalone C++)
add_executable(sor_perf_test
    test/performance_test.cpp
)

# Link performance test
target_link_libraries(sor_perf_test
    pthread
)

# SBE shared memory reader test (reads Java-written SBE messages)
add_executable(sbe_shm_reader_test
    test/sbe_shared_memory_reader_test.cpp
)

target_link_libraries(sbe_shm_reader_test
    pthread
)

if(USE_FULL_DEPENDENCIES)
    if(Boost_FOUND)
        target_link_libraries(sor_perf_test ${Boost_LIBRARIES})
    endif()
    if(folly_FOUND)
        target_link_libraries(sor_perf_test folly)
    endif()
endif()

# JNI shared library (optional)
if(BUILD_WITH_JNI)
    add_library(smartorderrouter SHARED ${SOR_SOURCES})

    target_link_libraries(smartorderrouter
        ${JNI_LIBRARIES}
        pthread
        dl
    )

    if(USE_FULL_DEPENDENCIES)
        if(Boost_FOUND)
            target_link_libraries(smartorderrouter ${Boost_LIBRARIES})
        endif()
        if(folly_FOUND)
            target_link_libraries(smartorderrouter folly)
        endif()
    endif()

    # Set library properties
    set_target_properties(smartorderrouter PROPERTIES
        VERSION 1.0.0
        SOVERSION 1
        PREFIX "lib"
    )

    message(STATUS "Building JNI shared library: libsmartorderrouter")
endif()

# Installation
install(TARGETS sor_perf_test
    RUNTIME DESTINATION bin
)

if(BUILD_WITH_JNI)
    install(TARGETS smartorderrouter
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin
    )
endif()

# Testing
enable_testing()

add_test(NAME sor_performance
         COMMAND sor_perf_test
         WORKING_DIRECTORY ${CMAKE_BINARY_DIR})

# Custom targets for easy building
add_custom_target(test_cpp
    COMMAND ./sor_perf_test
    DEPENDS sor_perf_test
    WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
    COMMENT "Running C++ SOR performance test"
)

# Build summary
message(STATUS "=== Smart Order Router Build Configuration ===")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "JNI Support: ${BUILD_WITH_JNI}")
message(STATUS "Full Dependencies: ${USE_FULL_DEPENDENCIES}")
if(BUILD_WITH_JNI)
    message(STATUS "JNI Include: ${JNI_INCLUDE_DIRS}")
endif()
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
message(STATUS "===============================================")
