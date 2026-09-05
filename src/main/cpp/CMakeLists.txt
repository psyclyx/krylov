cmake_minimum_required(VERSION 3.22)
project(krylov LANGUAGES CXX)
add_library(krylov SHARED krylov.cpp)
target_compile_features(krylov PRIVATE cxx_std_17)
