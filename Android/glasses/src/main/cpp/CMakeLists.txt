# Builds libglasses_bridge.so — the JNI bridge between Glashutte and the native VITURE SDK.
#
# Only configured when the VITURE SDK was supplied locally; glasses/build.gradle.kts passes
# -DVITURE_SDK_DIR=<sdk root> (containing libs/<abi>/*.so and include/*.h). The SDK is never
# committed to this repository.
cmake_minimum_required(VERSION 3.22.1)
project(glasses_bridge)

if(NOT DEFINED VITURE_SDK_DIR OR VITURE_SDK_DIR STREQUAL "")
    message(FATAL_ERROR "VITURE_SDK_DIR is not set. This CMake project is only built when the "
                        "VITURE SDK is supplied (see README); Gradle normally passes it.")
endif()

set(JNILIBS_DIR "${VITURE_SDK_DIR}/libs/${ANDROID_ABI}")
set(INCLUDE_DIR "${VITURE_SDK_DIR}/include")

# The proprietary VITURE SDK, consumed as a prebuilt shared library.
add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_DIR}/libglasses.so")

add_library(glasses_bridge SHARED glasses_bridge.cpp)
target_include_directories(glasses_bridge PRIVATE ${INCLUDE_DIR})
target_link_libraries(glasses_bridge glasses android log)
