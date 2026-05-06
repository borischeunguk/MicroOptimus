cmake_minimum_required(VERSION 3.20)

add_library(microoptimus_algo_cpp
    src/vwap_algorithm.cpp
)

target_include_directories(microoptimus_algo_cpp
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(microoptimus_algo_cpp
    PUBLIC
        microoptimus_common_cpp
)

add_executable(vwap_latency
    bench/vwap_latency.cpp
)

target_link_libraries(vwap_latency
    PRIVATE
        microoptimus_algo_cpp
        microoptimus_common_cpp
)

target_compile_definitions(vwap_latency
    PRIVATE
        ALGO_MODULE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}"
)

if(BUILD_TESTS AND GTest_FOUND)
    add_executable(algo_vwap_tests
        tests/vwap_algorithm_test.cpp
    )
    target_link_libraries(algo_vwap_tests
        PRIVATE
            microoptimus_algo_cpp
            GTest::gtest_main
    )
    add_test(NAME algo_vwap_tests COMMAND algo_vwap_tests)
endif()

