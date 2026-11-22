#!/usr/bin/env bash
# Builds TDLib (third_party/td, a pinned git submodule) for Android and drops the result into the
# :tdlib Gradle module:
#
#   tdlib/src/main/java/org/drinkless/tdlib/{Client,TdApi}.java   generated Java API
#   tdlib/src/main/jniLibs/<abi>/libtdjni.so                       native library, stripped
#
# Both outputs are gitignored: they are build products of the submodule commit, not sources.
# Re-run this after bumping third_party/td.
#
# It follows third_party/td/example/android/build-tdlib.sh, minus the parts TG9 doesn't need:
# no PHP (that only adds javadoc and androidx @IntDef/@Nullable annotations, and TG9 has no
# androidx), no javadoc, no zip. OpenSSL is built once per ABI as static libraries and cached in
# build/tdlib/openssl.
#
# Usage: scripts/build-tdlib.sh [abis]     default abis: "arm64-v8a armeabi-v7a"
#
# Environment: ANDROID_SDK_ROOT (default ~/Library/Android/sdk), JAVA_HOME (JDK 17+).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
ABIS="${1:-arm64-v8a armeabi-v7a}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
NDK_VERSION="27.2.12479018"
OPENSSL_VERSION="openssl-3.5.9"
# The suite's minSdk. TDLib itself would go lower; nothing in TG9 would.
API_LEVEL=34

NDK="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
TD="$ROOT/third_party/td"
TD_ANDROID="$TD/example/android"
WORK="$ROOT/build/tdlib"
OUT_JAVA="$ROOT/tdlib/src/main/java/org/drinkless/tdlib"
OUT_LIBS="$ROOT/tdlib/src/main/jniLibs"

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;  # the NDK ships universal binaries under this name
  Linux) HOST_TAG="linux-x86_64" ;;
  *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

for tool in cmake ninja gperf perl make java; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
[ -d "$NDK" ] || { echo "NDK $NDK_VERSION not found in $ANDROID_SDK_ROOT/ndk" >&2; exit 1; }
[ -f "$TD/CMakeLists.txt" ] || { echo "third_party/td is empty: git submodule update --init" >&2; exit 1; }

mkdir -p "$WORK"

# ── OpenSSL (static, per ABI, cached) ────────────────────────────────────────────────────────
build_openssl() {
  local abi="$1" target
  local prefix="$WORK/openssl/$abi"
  [ -f "$prefix/lib/libcrypto.a" ] && return 0
  case "$abi" in
    arm64-v8a) target=android-arm64 ;;
    armeabi-v7a) target=android-arm ;;
    x86_64) target=android-x86_64 ;;
    x86) target=android-x86 ;;
    *) echo "Unknown ABI: $abi" >&2; exit 1 ;;
  esac
  echo "==> OpenSSL $OPENSSL_VERSION for $abi"
  local src="$WORK/openssl-src"
  if [ ! -d "$src" ]; then
    local tarball="$WORK/$OPENSSL_VERSION.tar.gz"
    curl -sfL -o "$tarball" "https://github.com/openssl/openssl/releases/download/$OPENSSL_VERSION/$OPENSSL_VERSION.tar.gz"
    mkdir -p "$src"
    tar xzf "$tarball" -C "$src" --strip-components=1
    rm "$tarball"
  fi
  (
    cd "$src"
    export ANDROID_NDK_ROOT="$NDK"
    export PATH="$TOOLCHAIN/bin:$PATH"
    make distclean >/dev/null 2>&1 || true
    LDFLAGS="-Wl,-z,max-page-size=16384" ./Configure "$target" no-shared no-tests no-docs \
      -D__ANDROID_API__="$API_LEVEL" -ffunction-sections -fdata-sections >/dev/null
    make -j"$JOBS" -s build_libs >/dev/null
    mkdir -p "$prefix/lib"
    cp libcrypto.a libssl.a "$prefix/lib/"
    cp -R include "$prefix/"
    make distclean >/dev/null 2>&1 || true
  )
}

# ── Java API (host build of TDLib's code generators) ────────────────────────────────────────
echo "==> Generating TdApi.java"
cmake -S "$TD_ANDROID" -B "$WORK/native" -DTD_GENERATE_SOURCE_FILES=ON >/dev/null
cmake --build "$WORK/native" -j"$JOBS" >/dev/null
cmake --build "$WORK/native" --target tl_generate_java >/dev/null
mkdir -p "$OUT_JAVA"
mv "$TD_ANDROID/org/drinkless/tdlib/TdApi.java" "$OUT_JAVA/TdApi.java"
rm -rf "$TD_ANDROID/org"
cp "$TD/example/java/org/drinkless/tdlib/Client.java" "$OUT_JAVA/Client.java"

# ── Native library per ABI ───────────────────────────────────────────────────────────────────
for abi in $ABIS; do
  build_openssl "$abi"
  echo "==> libtdjni.so for $abi"
  build="$WORK/build-$abi"
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    armeabi-v7a) triple=arm-linux-androideabi ;;
    x86_64) triple=x86_64-linux-android ;;
    x86) triple=i686-linux-android ;;
  esac
  # Link the system's libz.so, which every Android ships. Left to itself, CMake's FindZLIB picks
  # the NDK's static libz.a on some hosts (GitHub's Linux runners), whose objects carry LTO
  # bitcode built with other flags than TDLib's -flto=thin, and the link fails with
  # "conflicting values for EnableSplitLTOUnit".
  cmake -S "$TD_ANDROID" -B "$build" -G Ninja \
    -DZLIB_USE_STATIC_LIBS=OFF \
    -DZLIB_LIBRARY="$TOOLCHAIN/sysroot/usr/lib/$triple/$API_LEVEL/libz.so" \
    -DZLIB_INCLUDE_DIR="$TOOLCHAIN/sysroot/usr/include" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DANDROID_ABI="$abi" \
    -DANDROID_STL=c++_static \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DOPENSSL_ROOT_DIR="$WORK/openssl/$abi" \
    -DOPENSSL_USE_STATIC_LIBS=TRUE \
    -DTD_ENABLE_PCH=ON >/dev/null
  cmake --build "$build" --target tdjni -j"$JOBS"
  mkdir -p "$OUT_LIBS/$abi"
  cp "$build/libtdjni.so" "$OUT_LIBS/$abi/libtdjni.so"
done

echo "==> Done: $(cd "$ROOT" && git -C third_party/td rev-parse --short HEAD) for $ABIS"
ls -l "$OUT_LIBS"/*/libtdjni.so
