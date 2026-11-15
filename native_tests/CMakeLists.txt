cmake_minimum_required(VERSION 3.22.1)
project(disksize_native_validation LANGUAGES CXX)

add_subdirectory(../android/app/src/main/cpp/core core)
add_executable(disksize_scan scan_cli.cpp)
target_link_libraries(disksize_scan PRIVATE disksize_core)
target_compile_options(disksize_scan PRIVATE -Wall -Wextra -Werror)

add_executable(disksize_native_tests scanner_tests.cpp)
target_link_libraries(disksize_native_tests PRIVATE disksize_core)
target_compile_options(disksize_native_tests PRIVATE -Wall -Wextra -Werror)

add_executable(disksize_root_pipe_tests root_pipe_tests.cpp)
target_link_libraries(disksize_root_pipe_tests PRIVATE disksize_core)
target_compile_options(disksize_root_pipe_tests PRIVATE -Wall -Wextra -Werror)

add_executable(disksize_stream_tests stream_tests.cpp)
target_link_libraries(disksize_stream_tests PRIVATE disksize_core)
target_compile_options(disksize_stream_tests PRIVATE -Wall -Wextra -Werror)
