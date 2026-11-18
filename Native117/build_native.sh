#!/bin/bash
# Compile the iOS 117 JNI bridge with Apple's GLES SDK.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || {
    printf 'Build the iOS native backend on a Mac with Xcode.\n' >&2
    exit 1
}
[[ $# == 2 ]] || {
    printf 'Usage: %s ANDROID_SOURCE_SNAPSHOT OUTPUT_RUNTIME_DIR\n' "$0" >&2
    exit 2
}
snapshot="$1"
output="$2"
root="$(cd "$(dirname "$0")/.." && pwd)"
[[ -d "$output" ]] || {
    printf 'The destination directory must exist.\n' >&2
    exit 1
}
workspace="$(mktemp -d)"
trap 'rm -rf -- "$workspace"' EXIT
python3 "$root/Native117/stage_native.py" "$snapshot" "$workspace/native"
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
xcrun --sdk iphoneos clang++ -arch arm64 -isysroot "$sdk" -miphoneos-version-min=17.4 \
    -std=c++17 -Wno-deprecated-declarations -Wno-writable-strings \
    -I"$root/Host/jni" -c "$workspace/native/Facade.cpp" -o "$workspace/Facade.o"
xcrun --sdk iphoneos clang++ -arch arm64 -isysroot "$sdk" -miphoneos-version-min=17.4 \
    -std=c++17 -fobjc-arc -Wno-deprecated-declarations \
    -c "$workspace/native/EaglContext.mm" -o "$workspace/EaglContext.o"
xcrun --sdk iphoneos clang++ -arch arm64 -isysroot "$sdk" -miphoneos-version-min=17.4 \
    -dynamiclib -install_name @rpath/libnewscape117.dylib \
    -framework OpenGLES -framework Foundation "$workspace/Facade.o" "$workspace/EaglContext.o" \
    -o "$workspace/libnewscape117.dylib"
cp "$workspace/libnewscape117.dylib" "$output/libnewscape117.dylib"
printf 'Built libnewscape117.dylib for arm64 iOS.\n'