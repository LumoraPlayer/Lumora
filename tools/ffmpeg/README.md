# Software audio decoders (FFmpeg)

Lumora's player uses the device's MediaCodec decoders first. Some TV sticks have no decoder
for audio the streams commonly carry - notably **MPEG-1/2 Layer II (MP2)**, which is standard
in DVB/IPTV, and DTS/TrueHD. For those the player falls back to the FFmpeg audio renderer
vendored in `media3-ffmpeg/`, and the native library built here.

This is a deliberately small FFmpeg: only the audio decoders below are compiled in. The full
FFmpeg/libmpv route was measured at +40-60 MB; this one costs ~3.6 MB of native libraries
across the three ABIs (about 1.1-1.3 MB each).

## What is built

- FFmpeg **6.0** (`https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz`,
  SHA-256 `57be87c22d9b49c112b6d24bc67d42508660e6b718b3db89c44e47e289137082`).
- Decoders: `mp1 mp2 mp3 ac3 eac3 dca truehd mlp` (DTS-HD/TrueHD included; the AC3/EAC3
  entries are a fallback for devices without Dolby MediaCodecs).
- Media3 1.4.1 `decoder_ffmpeg` Java sources (Apache-2.0) in `media3-ffmpeg/`; the native JNI
  wrapper and CMakeLists are in `tools/ffmpeg/jni/`.
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`. The prebuilt `libffmpegJNI.so` files live in
  `app/src/main/jniLibs/` so CI/release builds do not need the NDK or an FFmpeg checkout.

## Licence

FFmpeg is **LGPL 2.1 or later** (`COPYING.LGPLv2.1`). The build uses no `--enable-gpl`
components. The Media3 wrapper is Apache-2.0. Lumora is source-distributed, so the FFmpeg
source and the exact build scripts above are available for relinking/reproducing the build.
Do not add GPL-only codecs (x264, libmp3lame, etc.) without revisiting this notice - that
would change the licence of the whole binary, not just the decoder.

## Rebuilding

Prerequisites: Linux/macOS, `git curl make`, Android NDK (tested with r28), CMake.

```bash
# 1. Fetch and unpack FFmpeg 6.0 (the SHA-256 above).
WORK=/tmp/lumora-ffmpeg
mkdir -p "$WORK"
cd "$WORK"
curl -LO https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz
tar xf ffmpeg-6.0.tar.xz
mv ffmpeg-6.0 ffmpeg

# 2. Build the static FFmpeg libs for each ABI into $WORK/ffmpeg/android-libs/<abi>.
NDK=/opt/android-sdk/ndk/28.2.13676358 \
FFMPEG_SRC="$WORK/ffmpeg" \
  tools/ffmpeg/build_ffmpeg.sh

# 3. Build libffmpegJNI.so against those libs, then copy the results to jniLibs.
NDK=/opt/android-sdk/ndk/28.2.13676358 \
FFMPEG_SRC="$WORK/ffmpeg" \
REPO="$(pwd)" \
  tools/ffmpeg/build_jni.sh
```

To add or remove a decoder, edit `ENABLED_DECODERS` in `build_ffmpeg.sh` and the codec-name
mapping in `media3-ffmpeg/.../FfmpegLibrary.java`, then rebuild. The app must stay on the
Media3 version the vendored Java sources came from (1.4.1); bumping Media3 means
re-vendoring `libraries/decoder_ffmpeg` and rebuilding the native library.
