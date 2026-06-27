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

find_library(log-lib log)
target_link_libraries(robot_tsdf ${log-lib})
