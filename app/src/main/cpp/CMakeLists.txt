cmake_minimum_required(VERSION 3.10.2)
project(particle_material LANGUAGES CXX)
add_library(particle_material SHARED particle_material.cpp)
target_compile_features(particle_material PRIVATE cxx_std_17)
# 调试包也优化纯数值热循环；禁止融合和快速数学改写，保留原 float/double 交界。
target_compile_options(particle_material PRIVATE -O3 -ffp-contract=off -fno-fast-math)
set_target_properties(particle_material PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
