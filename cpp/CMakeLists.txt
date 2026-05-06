cmake_minimum_required(VERSION 3.20)
project(MicroOptimusCpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

option(BUILD_TESTS "Build C++ tests" ON)

if(BUILD_TESTS)
    find_package(GTest QUIET)
    if(GTest_FOUND)
        message(STATUS "System GTest found: C++ tests enabled")
    else()
        message(STATUS "System GTest not found: C++ tests skipped")
    endif()
endif()

add_subdirectory(common)
add_subdirectory(algo)
add_subdirectory(sor)

