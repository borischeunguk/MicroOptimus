cmake_minimum_required(VERSION 3.20)

add_library(microoptimus_sor_mvp_cpp
    src/smart_order_router.cpp
)

target_include_directories(microoptimus_sor_mvp_cpp
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(microoptimus_sor_mvp_cpp
    PUBLIC
        microoptimus_common_cpp
)

add_executable(router_latency
    bench/router_latency.cpp
)

target_link_libraries(router_latency
    PRIVATE
        microoptimus_sor_mvp_cpp
        microoptimus_common_cpp
)

target_compile_definitions(router_latency
    PRIVATE
        LIQUIDATOR_MODULE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}"
)

add_executable(e2e_algo_sor_latency
    bench/e2e_algo_sor_latency.cpp
)

target_link_libraries(e2e_algo_sor_latency
    PRIVATE
        microoptimus_sor_mvp_cpp
        microoptimus_algo_cpp
        microoptimus_common_cpp
)

target_include_directories(e2e_algo_sor_latency
    PRIVATE
        ${CMAKE_SOURCE_DIR}/algo/include
)

target_compile_definitions(e2e_algo_sor_latency
    PRIVATE
        LIQUIDATOR_MODULE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}"
)

if(BUILD_TESTS AND GTest_FOUND)
    add_executable(sor_mvp_tests
        tests/router_mvp_test.cpp
    )
    target_link_libraries(sor_mvp_tests
        PRIVATE
            microoptimus_sor_mvp_cpp
            GTest::gtest_main
    )
    add_test(NAME sor_mvp_tests COMMAND sor_mvp_tests)
endif()

