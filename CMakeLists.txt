cmake_minimum_required(VERSION 3.10)
project(MicroOptimus VERSION 1.0.0 LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Compiler flags
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -pedantic")
    set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_DEBUG "-g -O0")
endif()

# Include directories
include_directories(${CMAKE_SOURCE_DIR}/cpp/include)

# Source files
set(ORDERBOOK_SOURCES
    cpp/orderbook/OrderBook.cpp
)

set(MATCHENGINE_SOURCES
    cpp/matchengine/MatchEngine.cpp
)

# Create libraries
add_library(orderbook ${ORDERBOOK_SOURCES})
add_library(matchengine ${MATCHENGINE_SOURCES})

# Link libraries
target_link_libraries(matchengine orderbook)

# Main executable
add_executable(microoptimus cpp/main.cpp)
target_link_libraries(microoptimus matchengine orderbook)

# Enable threading support
find_package(Threads REQUIRED)
target_link_libraries(orderbook Threads::Threads)

# Installation rules
install(TARGETS microoptimus orderbook matchengine
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)

install(DIRECTORY cpp/include/
    DESTINATION include/microoptimus
    FILES_MATCHING PATTERN "*.hpp"
)

# Print build configuration
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ compiler: ${CMAKE_CXX_COMPILER}")
message(STATUS "C++ flags: ${CMAKE_CXX_FLAGS}")
