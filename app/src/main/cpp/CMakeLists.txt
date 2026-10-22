cmake_minimum_required(VERSION 3.22.1)
project(satb)

add_library(satb SHARED synth.cpp)
target_compile_options(satb PRIVATE -O2 -Wall)
target_link_libraries(satb aaudio log)
