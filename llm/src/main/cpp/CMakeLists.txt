cmake_minimum_required(VERSION 3.22)
project(hymt_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Debug APK'da bile optimize derle; yoksa çeviri 10 kat yavaşlar.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp bulunamadı: ${LLAMA_DIR}. Önce scripts/kurulum.sh çalıştırın.")
endif()

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(hymt SHARED hymt_jni.cpp)
target_link_libraries(hymt PRIVATE llama log android)
target_compile_options(hymt PRIVATE -O3 -fvisibility=hidden)
# whisper.cpp da kendi ggml kopyasını getiriyor; sembolleri dışarı açma ki iki kütüphane çakışmasın.
target_link_options(hymt PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -static-openmp)
