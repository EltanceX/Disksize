cmake_minimum_required(VERSION 3.22.1)
project(disksize_phase0_probe LANGUAGES CXX)

add_executable(disksize_phase0_probe probe.cpp)
target_compile_features(disksize_phase0_probe PRIVATE cxx_std_20)
target_compile_options(disksize_phase0_probe PRIVATE -Wall -Wextra -Werror)
