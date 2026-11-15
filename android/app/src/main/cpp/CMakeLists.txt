cmake_minimum_required(VERSION 3.22.1)
project(disksize LANGUAGES CXX)

add_subdirectory(core)

add_library(disksize_jni SHARED jni/native_bridge.cpp jni/scan_bridge.cpp)
target_link_libraries(disksize_jni PRIVATE disksize_core)
target_compile_options(disksize_jni PRIVATE -Wall -Wextra -Werror)
