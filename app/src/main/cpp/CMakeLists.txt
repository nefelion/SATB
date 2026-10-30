cmake_minimum_required(VERSION 3.22.1)
project(satb)

add_library(satb SHARED synth.cpp piano.cpp)
target_compile_options(satb PRIVATE -O2 -Wall)
# The piano runs hundreds of modes per sample: optimize it even in debug builds, and let its sums vectorize
set_source_files_properties(piano.cpp PROPERTIES COMPILE_OPTIONS "-O3;-ffast-math")
target_link_libraries(satb aaudio log)
