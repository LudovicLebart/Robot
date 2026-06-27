cmake_minimum_required(VERSION 3.22.1)
project(robot_tsdf CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    robot_tsdf
    SHARED
    jni_bridge.cpp
    tsdf_volume.cpp
    marching_cubes.cpp
)

target_include_directories(robot_tsdf PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Android 15 (Pixel 9): native .so files must be aligned to 16 KB page boundaries
target_link_options(robot_tsdf PRIVATE "-Wl,-z,max-page-size=16384")

target_compile_options(robot_tsdf PRIVATE -O3 -ffast-math)

find_library(log-lib log)
target_link_libraries(robot_tsdf ${log-lib})
