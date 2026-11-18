cmake_minimum_required(VERSION 3.22.1)
project(disksize LANGUAGES CXX)

add_subdirectory(core)

add_library(disksize_jni SHARED jni/native_bridge.cpp jni/scan_bridge.cpp)
target_link_libraries(disksize_jni PRIVATE disksize_core)
target_compile_options(disksize_jni PRIVATE -Wall -Wextra -Werror)

option(DISKSIZE_SCAN_BENCHMARK "Build native scanner with explicit optimization and symbols" OFF)
if(DISKSIZE_SCAN_BENCHMARK)
    foreach(target disksize_core disksize_jni)
        target_compile_options(${target} PRIVATE -O2 -g)
        target_compile_definitions(${target} PRIVATE NDEBUG)
    endforeach()
    target_compile_definitions(disksize_jni PRIVATE DISKSIZE_SCAN_BENCHMARK)
endif()
