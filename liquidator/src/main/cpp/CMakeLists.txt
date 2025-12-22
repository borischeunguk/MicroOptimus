cmake_minimum_required(VERSION 3.15)
project(SmartOrderRouter VERSION 1.0.0 LANGUAGES CXX)

# C++ Standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")

# Build options
option(BUILD_WITH_JNI "Build with JNI support" ON)
option(BUILD_TESTING "Build unit tests with Google Test" ON)
option(USE_BOOST "Use Boost libraries" OFF)
option(USE_FOLLY "Use Folly libraries" OFF)

# Find dependencies
if(USE_BOOST)
    # Try finding Boost with CONFIG mode first (modern CMake)
    find_package(Boost 1.70 CONFIG QUIET)
    if(NOT Boost_FOUND)
        # Fall back to MODULE mode
        find_package(Boost 1.70 COMPONENTS system thread)
    endif()

    if(Boost_FOUND)
        message(STATUS "Found Boost: ${Boost_VERSION}")
        add_definitions(-DWITH_BOOST)
    else()
        message(WARNING "Boost not found, building without Boost support")
        set(USE_BOOST OFF)
    endif()
endif()

if(USE_FOLLY)
    find_package(folly CONFIG)
    if(folly_FOUND)
        message(STATUS "Found Folly")
        add_definitions(-DWITH_FOLLY)
        # Folly requires C++20
        set(CMAKE_CXX_STANDARD 20)
        message(STATUS "Upgraded to C++20 for Folly compatibility")

        # Workaround for macOS SDK include path issues with Folly
        if(APPLE)
            # Remove problematic system SDK paths that Folly's config might add
            get_property(dirs DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} PROPERTY INCLUDE_DIRECTORIES)
            list(FILTER dirs EXCLUDE REGEX ".*/CommandLineTools/SDKs/.*")
            set_property(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} PROPERTY INCLUDE_DIRECTORIES ${dirs})
        endif()
    else()
        message(WARNING "Folly not found, building without Folly support")
        set(USE_FOLLY OFF)
    endif()
endif()

# Find JNI if enabled
if(BUILD_WITH_JNI)
    find_package(JNI)
    if(JNI_FOUND)
        message(STATUS "Building with JNI support")
        add_definitions(-DWITH_JNI)
    else()
        message(WARNING "JNI not found, building without JNI support")
        set(BUILD_WITH_JNI OFF)
    endif()
endif()

# Find Google Test if testing is enabled
if(BUILD_TESTING)
    find_package(GTest)
    if(NOT GTest_FOUND)
        # Try to use FetchContent to download Google Test
        include(FetchContent)
        FetchContent_Declare(
            googletest
            GIT_REPOSITORY https://github.com/google/googletest.git
            GIT_TAG v1.14.0
        )
        set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
        FetchContent_MakeAvailable(googletest)
        message(STATUS "Google Test fetched via FetchContent")
    else()
        message(STATUS "Found Google Test: ${GTest_VERSION}")
    endif()
    enable_testing()
endif()

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/sor/include
)

if(BUILD_WITH_JNI AND JNI_FOUND)
    include_directories(${JNI_INCLUDE_DIRS})
    message(STATUS "JNI Include: ${JNI_INCLUDE_DIRS}")
endif()

if(USE_BOOST AND Boost_FOUND)
    include_directories(${Boost_INCLUDE_DIRS})
endif()

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Wpedantic")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -march=native -mtune=native")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0 -DDEBUG")


# Source files for SOR library
set(SOR_SOURCES
    sor/src/venue_scorer.cpp
    sor/src/risk_manager.cpp
    sor/src/order_splitter.cpp
    sor/src/smart_order_router.cpp
)

# Add JNI wrapper if enabled
if(BUILD_WITH_JNI AND JNI_FOUND)
    list(APPEND SOR_SOURCES sor/src/jni_wrapper.cpp)
endif()

# Build shared library
add_library(smartorderrouter SHARED ${SOR_SOURCES})

set_target_properties(smartorderrouter PROPERTIES
    VERSION ${PROJECT_VERSION}
    SOVERSION 1
    PUBLIC_HEADER "sor/include/microoptimus/sor/smart_order_router.hpp"
)

# Link dependencies to library
target_link_libraries(smartorderrouter pthread)

if(BUILD_WITH_JNI AND JNI_FOUND)
    target_link_libraries(smartorderrouter ${JNI_LIBRARIES})
endif()

if(USE_BOOST AND Boost_FOUND)
    if(TARGET Boost::headers)
        # Modern CMake Boost (header-only is enough for circular_buffer)
        target_link_libraries(smartorderrouter Boost::headers)
    elseif(TARGET Boost::boost)
        target_link_libraries(smartorderrouter Boost::boost)
    elseif(Boost_INCLUDE_DIRS)
        # Legacy Boost
        target_include_directories(smartorderrouter PUBLIC ${Boost_INCLUDE_DIRS})
    endif()
endif()

if(USE_FOLLY AND folly_FOUND)
    target_link_libraries(smartorderrouter folly)
endif()

# Install targets
install(TARGETS smartorderrouter
    LIBRARY DESTINATION lib
    PUBLIC_HEADER DESTINATION include/microoptimus/sor
)

install(DIRECTORY sor/include/microoptimus
    DESTINATION include
    FILES_MATCHING PATTERN "*.hpp"
)

# Build tests
if(BUILD_TESTING)
    # Unit tests with Google Test
    add_executable(sor_unit_tests
        sor/tests/test_main.cpp
        sor/tests/test_venue_scorer.cpp
        sor/tests/test_risk_manager.cpp
        sor/tests/test_order_splitter.cpp
        sor/tests/test_smart_order_router.cpp
    )

    target_link_libraries(sor_unit_tests
        smartorderrouter
        GTest::gtest
        GTest::gtest_main
        pthread
    )

    if(USE_BOOST AND Boost_FOUND)
        if(TARGET Boost::headers)
            target_link_libraries(sor_unit_tests Boost::headers)
        elseif(TARGET Boost::boost)
            target_link_libraries(sor_unit_tests Boost::boost)
        elseif(Boost_INCLUDE_DIRS)
            target_include_directories(sor_unit_tests PUBLIC ${Boost_INCLUDE_DIRS})
        endif()
    endif()

    if(USE_FOLLY AND folly_FOUND)
        target_link_libraries(sor_unit_tests folly)
    endif()

    # Register tests with CTest
    include(GoogleTest)
    gtest_discover_tests(sor_unit_tests)

    # Performance test (standalone C++)
    add_executable(sor_perf_test test/performance_test.cpp)
    target_link_libraries(sor_perf_test smartorderrouter pthread)

    if(USE_BOOST AND Boost_FOUND)
        if(TARGET Boost::headers)
            target_link_libraries(sor_perf_test Boost::headers)
        elseif(TARGET Boost::boost)
            target_link_libraries(sor_perf_test Boost::boost)
        elseif(Boost_INCLUDE_DIRS)
            target_include_directories(sor_perf_test PUBLIC ${Boost_INCLUDE_DIRS})
        endif()
    endif()

    if(USE_FOLLY AND folly_FOUND)
        target_link_libraries(sor_perf_test folly)
    endif()

    # SBE shared memory reader test
    add_executable(sbe_shm_reader_test test/sbe_shared_memory_reader_test.cpp)
    target_link_libraries(sbe_shm_reader_test pthread)

    # Boost circular_buffer test (only if Boost is enabled and found)
    if(USE_BOOST AND Boost_FOUND)
        add_executable(boost_circular_buffer_test test/test_boost_circular_buffer.cpp)
        target_link_libraries(boost_circular_buffer_test
            GTest::gtest
            GTest::gtest_main
            pthread
        )

        # Link Boost (circular_buffer is header-only)
        if(TARGET Boost::headers)
            target_link_libraries(boost_circular_buffer_test Boost::headers)
        elseif(TARGET Boost::boost)
            target_link_libraries(boost_circular_buffer_test Boost::boost)
        elseif(Boost_INCLUDE_DIRS)
            target_include_directories(boost_circular_buffer_test PUBLIC ${Boost_INCLUDE_DIRS})
        endif()

        # Register with CTest
        gtest_discover_tests(boost_circular_buffer_test)

        message(STATUS "Boost circular_buffer test enabled")
    else()
        message(STATUS "Boost circular_buffer test disabled (USE_BOOST=${USE_BOOST}, Boost_FOUND=${Boost_FOUND})")
    endif()

    # Folly SPSC queue test (only if Folly is enabled and found)
    if(USE_FOLLY AND folly_FOUND)
        add_executable(folly_spsc_queue_test test/test_folly_spsc_queue.cpp)
        target_link_libraries(folly_spsc_queue_test
            GTest::gtest
            GTest::gtest_main
            Folly::folly
            pthread
        )

        # Workaround for macOS SDK include path issues with Folly
        # The issue is that Folly's CMake config adds system SDK paths that interfere
        # with C++ standard library headers. We need to ensure C++ headers come first.
        if(APPLE)
            target_compile_options(folly_spsc_queue_test PRIVATE
                # Remove any system SDK include that might be added by Folly
                -Wno-error
            )
            # Get the correct SDK path
            execute_process(
                COMMAND xcrun --show-sdk-path
                OUTPUT_VARIABLE MACOS_SDK_PATH
                OUTPUT_STRIP_TRAILING_WHITESPACE
            )
            # Explicitly set the include order: C++ stdlib first, then C stdlib
            target_include_directories(folly_spsc_queue_test SYSTEM BEFORE PRIVATE
                "${MACOS_SDK_PATH}/usr/include/c++/v1"
            )
        endif()

        # Register with CTest
        gtest_discover_tests(folly_spsc_queue_test)

        message(STATUS "Folly SPSC queue test enabled")
    else()
        message(STATUS "Folly SPSC queue test disabled (USE_FOLLY=${USE_FOLLY}, folly_FOUND=${folly_FOUND})")
    endif()

    # Add tests to CTest
    add_test(NAME UnitTests COMMAND sor_unit_tests)
    add_test(NAME PerformanceTest COMMAND sor_perf_test)

    # Custom test target
    set(TEST_DEPENDENCIES sor_unit_tests sor_perf_test)
    if(USE_BOOST AND Boost_FOUND)
        list(APPEND TEST_DEPENDENCIES boost_circular_buffer_test)
    endif()
    if(USE_FOLLY AND folly_FOUND)
        list(APPEND TEST_DEPENDENCIES folly_spsc_queue_test)
    endif()

    add_custom_target(run_tests
        COMMAND ${CMAKE_CTEST_COMMAND} --output-on-failure
        DEPENDS ${TEST_DEPENDENCIES}
        WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
        COMMENT "Running all tests"
    )
endif()

# Print configuration summary
message(STATUS "=== Smart Order Router Build Configuration ===")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "JNI Support: ${BUILD_WITH_JNI}")
message(STATUS "Boost Support: ${USE_BOOST}")
message(STATUS "Folly Support: ${USE_FOLLY}")
message(STATUS "Build Testing: ${BUILD_TESTING}")
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
message(STATUS "===============================================")

