cmake_minimum_required(VERSION 3.22.1)
project(disksize LANGUAGES CXX)

add_subdirectory(core)

add_library(disksize_jni SHARED jni/native_bridge.cpp jni/scan_bridge.cpp)
target_link_libraries(disksize_jni PRIVATE disksize_core)
target_compile_options(disksize_jni PRIVATE -Wall -Wextra -Werror)

option(DISKSIZE_SCAN_BENCHMARK "Build native scanner with explicit optimization and symbols" OFF)
# AGP uses the custom lowercase "profile" configuration. CMake has no default
# optimization flags for it; ordinary Profile builds need the same optimized
# scanner even when all benchmark entry points are disabled.
if(DISKSIZE_SCAN_BENCHMARK OR CMAKE_BUILD_TYPE STREQUAL "profile")
    foreach(target disksize_core disksize_jni)
        target_compile_options(${target} PRIVATE -O2 -g)
        target_compile_definitions(${target} PRIVATE NDEBUG)
    endforeach()
endif()
if(DISKSIZE_SCAN_BENCHMARK)
    target_compile_definitions(disksize_jni PRIVATE DISKSIZE_SCAN_BENCHMARK)
endif()
