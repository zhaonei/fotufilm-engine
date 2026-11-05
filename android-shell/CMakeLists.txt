cmake_minimum_required(VERSION 3.20)
project(libfotufilm CXX)

# The arm64 library the shell loads. It compiles the JNI bridge and the engine behind it; which
# engine that is depends on what the build machine can produce -- see README.md.
#
# On a normal (x86-64) machine the Android NDK drives CMake:
#
#   cmake -S . -B build -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
#     -DFOTUFILM_REAL_ENGINE=/path/to/flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so
#
# On hosts the NDK ships no compiler for (aarch64 Linux), cmake/host-aarch64-android28.cmake
# drives this machine's own clang against the NDK's target pieces instead.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(FOTUFILM_PLACEHOLDER_ENGINE
  "Compile jni/fotufilm_engine_stub.cpp instead of linking the real Swift/Halide engine" ON)

# The engine's public C interface; the bridge only ever speaks this.
set(FOTUFILM_API_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../Sources/CFotufilmHost/include")
if(NOT EXISTS "${FOTUFILM_API_INCLUDE}/fotufilm.h")
  message(FATAL_ERROR "fotufilm.h not found in ${FOTUFILM_API_INCLUDE}; "
                      "run this from inside the fotufilm-engine checkout")
endif()

# jni.h: the NDK carries its own under the sysroot and that is the right one to use; a JDK's
# include/ (+ include/linux for jni_md.h) is the fallback when running outside the NDK.
find_path(FOTUFILM_JNI_INCLUDE jni.h
  HINTS "$ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
        "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/../include"
  PATHS /usr/lib/jvm/java-17-openjdk/include /usr/lib/jvm/default-java/include
  DOC "jni.h")
if(NOT FOTUFILM_JNI_INCLUDE)
  message(FATAL_ERROR "jni.h not found; set JAVA_HOME to a JDK or ANDROID_NDK_HOME to the NDK")
endif()
find_path(FOTUFILM_JNI_PLATFORM_INCLUDE jni_md.h
  HINTS "$ENV{JAVA_HOME}/include/linux" "$ENV{JAVA_HOME}/../include/linux"
  PATHS /usr/lib/jvm/java-17-openjdk/include/linux
  DOC "jni_md.h (only the JDK splits it out; the NDK's jni.h is self-contained)")

set(FOTUFILM_SOURCES jni/fotufilm_jni.cpp)
if(FOTUFILM_PLACEHOLDER_ENGINE)
  list(APPEND FOTUFILM_SOURCES jni/fotufilm_engine_stub.cpp)
endif()

add_library(fotufilm SHARED ${FOTUFILM_SOURCES})
target_include_directories(fotufilm PRIVATE "${FOTUFILM_API_INCLUDE}" "${FOTUFILM_JNI_INCLUDE}")
if(FOTUFILM_JNI_PLATFORM_INCLUDE)
  target_include_directories(fotufilm PRIVATE "${FOTUFILM_JNI_PLATFORM_INCLUDE}")
endif()

target_compile_options(fotufilm PRIVATE
  -O2 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)

set_target_properties(fotufilm PROPERTIES
  OUTPUT_NAME fotufilm
  PREFIX "lib"
  SUFFIX ".so"
  LINK_DEPENDS ""
  CXX_VISIBILITY_PRESET hidden)

# --gc-sections and the 16 KB page size Android 15 wants for every shared object.
target_link_options(fotufilm PRIVATE
  -Wl,--gc-sections
  -Wl,-soname,libfotufilm.so
  -Wl,--build-id=sha1
  -Wl,-z,max-page-size=16384)

# The host-native driver (cmake/host-aarch64-android28.cmake) points libc++ at the NDK's static
# archives, because the device has no libc++_shared.so and this host's clang++ would otherwise
# link against it.
if(FOTUFILM_LIBCXX_INCLUDE)
  target_compile_options(fotufilm PRIVATE -nostdinc++ -isystem "${FOTUFILM_LIBCXX_INCLUDE}")
endif()
if(FOTUFILM_LIBCXX_STATIC)
  target_link_options(fotufilm PRIVATE -nostdlib++)
  target_link_libraries(fotufilm PRIVATE ${FOTUFILM_LIBCXX_STATIC})
  target_link_options(fotufilm PRIVATE -Wl,--exclude-libs,ALL)
endif()

# The real engine brings its own runtime and FFmpeg; when it is linked in, the placeholder is
# compiled out and the library is the engine itself.
if(FOTUFILM_REAL_ENGINE)
  if(NOT EXISTS "${FOTUFILM_REAL_ENGINE}")
    message(FATAL_ERROR "FOTUFILM_REAL_ENGINE points at nothing: ${FOTUFILM_REAL_ENGINE}")
  endif()
  set_target_properties(fotufilm PROPERTIES
    INSTALL_RPATH "$ORIGIN"
    BUILD_WITH_INSTALL_RPATH ON)
endif()
