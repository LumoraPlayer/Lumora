#!/bin/bash
# Build libffmpegJNI.so for each ABI against the FFmpeg static libs from
# build_ffmpeg.sh, strip it, and copy it into app/src/main/jniLibs/.
#
# Usage:
#   NDK=/path/to/ndk FFMPEG_SRC=/path/to/ffmpeg-6.0 ./tools/ffmpeg/build_jni.sh
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FFMPEG_SRC="${FFMPEG_SRC:-$ROOT/../ffmpeg-6.0}"
NDK="${NDK:-${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.2.13676358}}"
BUILD_DIR="${BUILD_DIR:-$ROOT/build/ffmpeg-jni}"
STRIP="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

for abi in armeabi-v7a arm64-v8a x86_64; do
    cmake -S "$ROOT/tools/ffmpeg/jni" -B "$BUILD_DIR/$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DFFMPEG_SRC="$FFMPEG_SRC" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-${API:-25}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$BUILD_DIR/out/$abi" >/dev/null
    cmake --build "$BUILD_DIR/$abi" -j"$(nproc)" >/dev/null
    "$STRIP" --strip-unneeded "$BUILD_DIR/out/$abi/libffmpegJNI.so"
    mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
    cp "$BUILD_DIR/out/$abi/libffmpegJNI.so" "$ROOT/app/src/main/jniLibs/$abi/"
    ls -lh "$ROOT/app/src/main/jniLibs/$abi/libffmpegJNI.so"
done
