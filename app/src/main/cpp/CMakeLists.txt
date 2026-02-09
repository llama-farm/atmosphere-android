# CMakeLists.txt for llama.cpp JNI bindings
# 
# To use this, you need:
# 1. llama.cpp source code (clone from https://github.com/ggml-org/llama.cpp)
# 2. Set LLAMA_CPP_DIR environment variable or edit the path below
# 3. Enable NDK in build.gradle.kts

cmake_minimum_required(VERSION 3.22.1)
project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to llama.cpp source - adjust this or set LLAMA_CPP_DIR env var
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/../../../../llama.cpp" CACHE PATH "Path to llama.cpp source")

if(NOT EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}. Please set LLAMA_CPP_DIR.")
endif()

# Include directories
include_directories(
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

# Android log library
find_library(log-lib log)

# Create the JNI library
add_library(llama-jni SHARED
    llama_jni.cpp
)

# Link against prebuilt llama.so from the AAR's jni folder
# This assumes the AAR has been extracted and libs are available
target_link_libraries(llama-jni
    ${log-lib}
    # Note: llama.so, ggml.so etc. will be loaded dynamically
)

# Compiler flags
target_compile_options(llama-jni PRIVATE
    -Wall
    -Wextra
    -O3
    -ffast-math
)
