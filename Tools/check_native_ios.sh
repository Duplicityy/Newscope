#!/bin/bash
# Compile and link the actual iOS host and both native rendering backends.
set -euo pipefail

if [[ $# != 2 ]]; then
    printf 'Usage: bash %s ANDROID_NATIVE_SNAPSHOT NEW_OUTPUT_DIRECTORY\n' "$0" >&2
    exit 2
fi
if [[ "$(uname -s)" != Darwin ]]; then
    printf 'Run this build on a macOS runner with Xcode and the iPhoneOS SDK.\n' >&2
    exit 1
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
snapshot="$(cd "$1" && pwd)"
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
xcodebuild -version
printf 'iPhoneOS SDK %s\n' "$(xcrun --sdk iphoneos --show-sdk-version)"

mkdir -- "$2"
output="$(cd "$2" && pwd)"
bash "$root/Native117/build_native.sh" "$snapshot" "$output"

# Keep the device target and frameworks aligned with project.yml.
target=(-arch arm64 -isysroot "$sdk" -miphoneos-version-min=17.4)
include=(-I"$root/Host/jni")
objects=()
for source in Host/main.m Host/PluginControls.m Host/GameRuntime.mm MobileHD/MobileHdContext.mm MobileHD/MobileHD.cpp; do
    object="$output/$(basename "$source").o"
    printf 'Compiling %s\n' "$source"
    case "$source" in
        *.m)
            xcrun --sdk iphoneos clang "${target[@]}" "${include[@]}" -fobjc-arc -fblocks \
                -c "$root/$source" -o "$object"
            ;;
        *.mm)
            xcrun --sdk iphoneos clang++ "${target[@]}" "${include[@]}" -std=c++17 -fobjc-arc -fblocks \
                -Wno-deprecated-declarations -c "$root/$source" -o "$object"
            ;;
        *.cpp)
            xcrun --sdk iphoneos clang++ "${target[@]}" "${include[@]}" -std=c++17 \
                -Wno-deprecated-declarations -c "$root/$source" -o "$object"
            ;;
    esac
    objects+=("$object")
done
xcrun --sdk iphoneos clang++ "${target[@]}" "${objects[@]}" \
    -framework Foundation -framework UIKit -framework QuartzCore -framework CoreGraphics \
    -framework AVFoundation -framework ImageIO -framework OpenGLES \
    -o "$output/Newscape-native-check"

python3 - "$root/Tools" "$output" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from stage_runtime import PLATFORMS, mach_platform

output = Path(sys.argv[2])
for binary in ("Newscape-native-check", "libnewscape117.dylib"):
    if mach_platform(output / binary) != PLATFORMS["device"]:
        raise SystemExit(f"{binary} does not target arm64 iOS devices")
    print(f"Verified arm64 iOS device linkage for {binary}")
PY
printf 'PASS iOS native compile and link. Device launch and graphics performance are pending.\n'