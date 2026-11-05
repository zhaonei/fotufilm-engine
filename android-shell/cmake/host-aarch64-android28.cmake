# Cross-compiling for Android arm64 (API 28) on an aarch64 Linux host.
#
# The Android NDK ships no compiler that runs here -- its prebuilts are x86-64 ELF -- but the
# NDK's *target* pieces are host-independent: the bionic sysroot, libc++ built for android,
# compiler-rt's builtins and libunwind are all aarch64 code. So this host's own clang is used as
# the driver with --target pointing at the sysroot, through two small wrapper scripts in
# ../toolchain that also carry the fixes for musl's -lssp_nonshared and for clang++'s default
# -lc++ (which would resolve to the libc++_shared.so Android does not have).
#
#   cmake -S . -B build -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=cmake/host-aarch64-android28.cmake
set(CMAKE_SYSTEM_NAME Linux)          # cross-compiling; CMAKE_SYSTEM_NAME Android would demand NDK
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(FOTUFILM_NDK "$ENV{ANDROID_NDK_HOME}" CACHE PATH "Android NDK (r26+ layout)")
if(NOT FOTUFILM_NDK)
  message(FATAL_ERROR "set ANDROID_NDK_HOME to an unpacked Android NDK")
endif()
get_filename_component(_prebuilt "${FOTUFILM_NDK}/toolchains/llvm/prebuilt/linux-x86_64" ABSOLUTE)
set(_sysroot "${_prebuilt}/sysroot")
if(NOT EXISTS "${_sysroot}/usr/include/android")
  message(FATAL_ERROR "no NDK sysroot at ${_sysroot}")
endif()

set(FOTUFILM_TOOLCHAIN "${CMAKE_CURRENT_LIST_DIR}/../toolchain")
set(CMAKE_C_COMPILER   "${FOTUFILM_TOOLCHAIN}/aarch64-linux-android28-clang")
set(CMAKE_CXX_COMPILER "${FOTUFILM_TOOLCHAIN}/aarch64-linux-android28-clang++")

# libc++ for android, statically: the device ships no libc++_shared.so.
set(FOTUFILM_LIBCXX_INCLUDE "${_sysroot}/usr/include/c++/v1" CACHE PATH "")
set(FOTUFILM_LIBCXX_STATIC
    "${_sysroot}/usr/lib/aarch64-linux-android/libc++_static.a"
    "${_sysroot}/usr/lib/aarch64-linux-android/libc++abi.a" CACHE STRING "")

set(CMAKE_FIND_ROOT_PATH "${_sysroot}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
