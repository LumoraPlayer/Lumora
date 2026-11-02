#!/bin/bash
# Build the minimal FFmpeg 6.0 static libraries used by libffmpegJNI.so.
#
# Usage:
#   NDK=/path/to/ndk FFMPEG_SRC=/path/to/ffmpeg-6.0 ./tools/ffmpeg/build_ffmpeg.sh
#
# FFMPEG_SRC must be an unpacked FFmpeg 6.0 source tree. Outputs land in
# $FFMPEG_SRC/android-libs/<abi>/lib and .../include.
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FFMPEG_SRC="${FFMPEG_SRC:-$ROOT/../ffmpeg-6.0}"
NDK="${NDK:-${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.2.13676358}}"
HOST_PLATFORM="${HOST_PLATFORM:-linux-x86_64}"
API="${API:-25}"
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
ENABLED_DECODERS=(mp1 mp2 mp3 ac3 eac3 dca truehd mlp)

COMMON_OPTIONS="
    --target-os=android
    --enable-static
    --disable-shared
    --disable-doc
    --disable-programs
    --disable-everything
    --disable-avdevice
    --disable-avformat
    --disable-swscale
    --disable-postproc
    --disable-avfilter
    --disable-symver
    --enable-swresample
    --disable-network
    --disable-autodetect
    --disable-zlib
    --disable-bzlib
    --disable-lzma
    --disable-iconv
    --disable-v4l2-m2m
    --disable-vulkan
"
for decoder in "${ENABLED_DECODERS[@]}"; do
    COMMON_OPTIONS="$COMMON_OPTIONS --enable-decoder=${decoder}"
done

TOOLCHAIN_PREFIX="${NDK}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"

configure_and_make() {
    local libdir="$1"
    shift
    cd "$FFMPEG_SRC"
    ./configure \
        --libdir="$libdir" \
        --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
        --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
        --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
        --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
        "$@" \
        ${COMMON_OPTIONS}
    make -j"$JOBS"
    make install-libs
    make clean
}

build_armv7() {
    configure_and_make android-libs/armeabi-v7a \
        --arch=arm \
        --cpu=armv7-a \
        --cross-prefix="${TOOLCHAIN_PREFIX}/armv7a-linux-androideabi${API}-" \
        --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
        --extra-ldflags="-Wl,--fix-cortex-a8"
}

build_arm64() {
    configure_and_make android-libs/arm64-v8a \
        --arch=aarch64 \
        --cpu=armv8-a \
        --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android${API}-"
}

build_x86_64() {
    configure_and_make android-libs/x86_64 \
        --arch=x86_64 \
        --cpu=x86-64 \
        --cross-prefix="${TOOLCHAIN_PREFIX}/x86_64-linux-android${API}-" \
        --disable-asm
}

case "${1:-all}" in
    armv7) build_armv7 ;;
    arm64) build_arm64 ;;
    x86_64) build_x86_64 ;;
    all)
        build_armv7
        build_arm64
        build_x86_64
        ;;
    *) echo "usage: $0 [armv7|arm64|x86_64|all]"; exit 2 ;;
esac
