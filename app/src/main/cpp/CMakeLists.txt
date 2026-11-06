cmake_minimum_required(VERSION 3.22.1)
project(harness_local LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE Release CACHE STRING "Native inference requires optimization" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Wireless debugging pairing. Built for every ABI because it is independent of
# the llama backend, which drops 32-bit builds further down.
if(ANDROID)
    add_library(harness_device SHARED device_info.cpp)
    target_link_libraries(harness_device PRIVATE dl)
    target_link_options(harness_device PRIVATE "-Wl,-z,max-page-size=16384")
    # Pseudo-terminal for the interactive Terminal. Independent of llama, so
    # every ABI gets it.
    add_library(harness_pty SHARED pty.cpp)
    target_link_options(harness_pty PRIVATE "-Wl,-z,max-page-size=16384")
    find_package(boringssl REQUIRED CONFIG)
    add_library(harness_adb SHARED adb_pairing.cpp)
    target_link_libraries(harness_adb PRIVATE log boringssl::crypto_static)
    target_link_options(harness_adb PRIVATE "-Wl,-z,max-page-size=16384")
endif()
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    add_library(harness_local SHARED local_unavailable.cpp)
    target_link_options(harness_local PRIVATE "-Wl,-z,max-page-size=16384")
    add_library(harness_gpu_unavailable SHARED gpu_unavailable.cpp)
    target_link_options(harness_gpu_unavailable PRIVATE "-Wl,-z,max-page-size=16384")
    if(DEFINED HARNESS_GPU_OUTPUT)
        get_filename_component(harness_config_dir "${CMAKE_BINARY_DIR}/../.." ABSOLUTE)
        get_filename_component(harness_config "${harness_config_dir}" NAME)
        add_custom_target(harness_gpu_package
            COMMAND ${CMAKE_COMMAND} -E make_directory "${HARNESS_GPU_OUTPUT}/${harness_config}/${ANDROID_ABI}"
            COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:harness_gpu_unavailable> "${HARNESS_GPU_OUTPUT}/${harness_config}/${ANDROID_ABI}/libharness_gpu_unavailable.so"
            DEPENDS harness_gpu_unavailable)
        add_dependencies(harness_local harness_gpu_package)
    endif()
    return()
endif()
include(FetchContent)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Arm CPU kernels are selected at runtime when the device and operation support
# them; other operations continue to use the regular CPU implementation.
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_FMA OFF CACHE BOOL "" FORCE)
set(GGML_F16C OFF CACHE BOOL "" FORCE)
# Vulkan uses Android's system driver; build-time headers/shader tools stay off-device.
if(ANDROID AND (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
    FetchContent_Declare(vulkan_headers
        URL https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/v1.4.363
        URL_HASH SHA256=cbaf687d3c59b9666fe080e7f8396b6b4f4d344a768ee6b337c59852f4526b68)
    FetchContent_Declare(spirv_headers
        URL https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/vulkan-sdk-1.4.341.0
        URL_HASH SHA256=cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c)
    FetchContent_MakeAvailable(vulkan_headers spirv_headers)
    set(Vulkan_INCLUDE_DIR "${vulkan_headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)
    find_library(ANDROID_VULKAN_LIBRARY vulkan REQUIRED)
    set(Vulkan_LIBRARY "${ANDROID_VULKAN_LIBRARY}" CACHE FILEPATH "" FORCE)
    find_program(HOST_GLSLC glslc NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(Vulkan_GLSLC_EXECUTABLE "${HOST_GLSLC}" CACHE FILEPATH "" FORCE)
    # Upstream expects the package config, while FetchContent already defines its target.
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/spirv-config")
    file(WRITE "${CMAKE_BINARY_DIR}/spirv-config/SPIRV-HeadersConfig.cmake" "set(SPIRV-Headers_FOUND TRUE)\n")
    set(SPIRV-Headers_DIR "${CMAKE_BINARY_DIR}/spirv-config")
    include_directories("${spirv_headers_SOURCE_DIR}/include")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()
set(LLAMA_REVISION 19cce6919992fdffd92e0ac152caaf1e2b8f254e)
FetchContent_Declare(llama
    BINARY_DIR "${CMAKE_BINARY_DIR}/llama-${LLAMA_REVISION}"
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/19cce6919992fdffd92e0ac152caaf1e2b8f254e
    URL_HASH SHA256=e16c6d1076af7a2bdb351e22176dd4b302f244ac4c3822604e2ec64a8a783d44
)
FetchContent_MakeAvailable(llama)
if(TARGET ggml-vulkan)
    target_link_options(ggml-vulkan PRIVATE "-Wl,-soname,libggml-vulkan.so" "-Wl,-z,max-page-size=16384")
    # The pinned backend has three direct Vulkan 1.1 calls. Resolve them through
    # its existing instance dispatcher so the CPU library still loads on API 26.
    set(vulkan_source "${llama_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    file(READ "${vulkan_source}" vulkan_original)
    string(REPLACE "    vkGetPhysicalDeviceFeatures2(" "    VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(" vulkan_patched "${vulkan_original}")
    if(NOT vulkan_original STREQUAL vulkan_patched)
        file(WRITE "${vulkan_source}" "${vulkan_patched}")
    endif()
endif()
add_library(harness_local SHARED local_model.cpp local_chat.cpp safetensors_import.cpp)
if(TARGET ggml-vulkan AND DEFINED HARNESS_GPU_OUTPUT)
    get_filename_component(harness_config_dir "${CMAKE_BINARY_DIR}/../.." ABSOLUTE)
    get_filename_component(harness_config "${harness_config_dir}" NAME)
    set(harness_gpu_dir "${HARNESS_GPU_OUTPUT}/${harness_config}/${ANDROID_ABI}")
    add_custom_target(harness_gpu_package
        COMMAND ${CMAKE_COMMAND} -E make_directory "${harness_gpu_dir}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:ggml-vulkan> "${harness_gpu_dir}/libggml-vulkan.so"
        DEPENDS ggml-vulkan)
    add_dependencies(harness_local harness_gpu_package)
endif()
target_link_libraries(harness_local PRIVATE llama llama-common mtmd)
if(ANDROID)
    target_link_libraries(harness_local PRIVATE android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(harness_local PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_link_options(harness_local PRIVATE "-Wl,-z,max-page-size=16384")
