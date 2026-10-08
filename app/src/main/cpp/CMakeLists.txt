cmake_minimum_required(VERSION 3.22.1)
project(saridub LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(saridub SHARED dsp.cpp jni_bridge.cpp)
target_compile_definitions(saridub PRIVATE _FILE_OFFSET_BITS=64)
target_compile_options(saridub PRIVATE -O3 -Wall -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(saridub PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
