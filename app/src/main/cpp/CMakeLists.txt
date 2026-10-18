cmake_minimum_required(VERSION 3.22)
project(omamobile_host C CXX ASM)
set(VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(TAWC_ARCH aarch64)
    set(TAWC_BASE 0x2000000000)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(TAWC_ARCH x86_64)
    set(TAWC_BASE 0x40000000)
else()
    message(FATAL_ERROR "Only upstream ARM64 and x86_64 runtimes are supported")
endif()

set(HYPRLAND_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilt/${ANDROID_ABI}/libanhyprland.so")
file(GLOB HYPRLAND_INPUT CONFIGURE_DEPENDS "${HYPRLAND_LIB}")
if(HYPRLAND_INPUT)
    add_library(anhyprland SHARED IMPORTED)
    set_target_properties(anhyprland PROPERTIES IMPORTED_LOCATION "${HYPRLAND_LIB}")
    add_library(omamobile_hyprland SHARED hyprland_bridge.cpp)
    target_compile_options(omamobile_hyprland PRIVATE -O2 -Wall -Wextra)
    target_link_options(omamobile_hyprland PRIVATE -Wl,-z,max-page-size=16384)
    target_link_libraries(omamobile_hyprland anhyprland android log)
    if(CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_sources(omamobile_hyprland PRIVATE ../../debug/cpp/unified_spike.cpp)
        target_link_libraries(omamobile_hyprland dl)
    endif()
endif()
# MIT upstream runtime plus the recorded outbound-credential adapter.
# Stable ET_EXEC address is part of its seccomp ABI.
file(GLOB TAWC_SOURCES CONFIGURE_DEPENDS "${VENDOR}/tawcroot/src/*.c")
add_executable(tawcroot ${TAWC_SOURCES}
    "${VENDOR}/tawcroot/src/arch/${TAWC_ARCH}_stub.S"
    "${VENDOR}/tawcroot/src/arch/${TAWC_ARCH}_loader_jump.S")
set_target_properties(tawcroot PROPERTIES OUTPUT_NAME libtawcroot.so
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_include_directories(tawcroot PRIVATE "${VENDOR}/tawcroot/include")
target_compile_options(tawcroot PRIVATE -O2 -fno-strict-aliasing -fno-stack-protector
    -fvisibility=hidden -fno-exceptions -fno-asynchronous-unwind-tables -ffreestanding
    -Wall -Wextra -Werror -Wframe-larger-than=1024)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(tawcroot PRIVATE -mno-outline-atomics)
endif()
target_link_options(tawcroot PRIVATE -static -nostdlib -nostartfiles
    -Wl,--build-id=none -Wl,-z,noexecstack -Wl,-z,relro
    -Wl,-z,max-page-size=16384 "-Wl,--image-base=${TAWC_BASE}" -Wl,-soname,libtawcroot.so)

add_executable(ando "${VENDOR}/ando/ando.c" "${VENDOR}/ando/ando_broker.c")
set_target_properties(ando PROPERTIES OUTPUT_NAME libando.so
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_compile_options(ando PRIVATE -O2 -Wall -Wextra -Werror)
target_link_options(ando PRIVATE -static -Wl,-z,max-page-size=16384)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_executable(runtime_probe runtime_probe.c)
    set_target_properties(runtime_probe PROPERTIES OUTPUT_NAME libruntime_probe.so
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    target_compile_options(runtime_probe PRIVATE -O2 -Wall -Wextra -Werror)
    target_link_options(runtime_probe PRIVATE -static -Wl,-z,max-page-size=16384)
endif()
