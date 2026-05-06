cmake_minimum_required(VERSION 3.20)

add_library(microoptimus_common_cpp INTERFACE)

target_include_directories(microoptimus_common_cpp
    INTERFACE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

