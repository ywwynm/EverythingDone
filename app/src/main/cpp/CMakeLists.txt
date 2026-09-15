cmake_minimum_required(VERSION 3.10.2)
project(particle_material LANGUAGES CXX)
add_library(particle_material SHARED particle_material.cpp particle_frame_timestamps.cpp)
target_compile_features(particle_material PRIVATE cxx_std_17)
# 调试包也优化纯数值热循环；禁止融合和快速数学改写，保留原 float/double 交界。
target_compile_options(particle_material PRIVATE -O3 -ffp-contract=off -fno-fast-math)
# EGL_ANDROID_get_frame_timestamps 的入口经 eglGetProcAddress 取，但 eglSurfaceAttrib
# 与 eglGetProcAddress 本身需要链接 libEGL。
target_link_libraries(particle_material EGL)
set_target_properties(particle_material PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
