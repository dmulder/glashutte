# Builds libglasses_bridge.so — the JNI bridge between Glashutte and the native VITURE SDK.
cmake_minimum_required(VERSION 3.22.1)
project(glasses_bridge)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

# The proprietary VITURE SDK, consumed as a prebuilt shared library.
add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_DIR}/libglasses.so")

add_library(glasses_bridge SHARED glasses_bridge.cpp)
target_include_directories(glasses_bridge PRIVATE ${INCLUDE_DIR})
target_link_libraries(glasses_bridge glasses android log)
