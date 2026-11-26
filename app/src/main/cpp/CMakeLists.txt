cmake_minimum_required(VERSION 3.22.1)
project(kai_super_resolution LANGUAGES CXX)

add_library(kai_upscale SHARED super_resolution.cpp)
target_compile_features(kai_upscale PRIVATE cxx_std_17)
target_compile_options(kai_upscale PRIVATE -Wall -Wextra -fvisibility=hidden)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(ncnn_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")
    find_package(ncnn CONFIG REQUIRED)
    # Preserve the bridge's handling of allocation failures when consuming the prebuilt library.
    get_target_property(ncnn_compile_options ncnn INTERFACE_COMPILE_OPTIONS)
    list(REMOVE_ITEM ncnn_compile_options "-fno-exceptions")
    set_property(TARGET ncnn PROPERTY INTERFACE_COMPILE_OPTIONS "${ncnn_compile_options}")
    target_compile_definitions(kai_upscale PRIVATE KAI_HAS_NCNN)
    target_link_libraries(kai_upscale PRIVATE ncnn android jnigraphics log)
    target_link_options(kai_upscale PRIVATE "-Wl,--exclude-libs,ALL")
endif()
