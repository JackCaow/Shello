#!/usr/bin/env bash
# Builds ios/Frameworks/parts/GhosttyVT.xcframework (device + simulator) from the pinned Ghostty commit.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${ZIG_BIN:=zig}"
: "${GHOSTTY_SOURCE:=$root/.native/ghostty}"
commit=b58ef1d678be2747c830bc1b1e6c0a086afecf9a
min=16.0
[[ $("$ZIG_BIN" version) == 0.16.0 ]] || { echo 'Zig 0.16.0 required' >&2; exit 1; }
if [[ ! -d "$GHOSTTY_SOURCE" ]]; then
 mkdir -p "$GHOSTTY_SOURCE"
 git -C "$GHOSTTY_SOURCE" init -q
 git -C "$GHOSTTY_SOURCE" fetch --depth 1 https://github.com/ghostty-org/ghostty.git "$commit"
 git -C "$GHOSTTY_SOURCE" checkout --detach FETCH_HEAD
fi
[[ $(git -C "$GHOSTTY_SOURCE" rev-parse HEAD) == "$commit" ]] || { echo "Expected Ghostty $commit" >&2; exit 1; }
work=$(mktemp -d "${TMPDIR:-/tmp}/shello-ios.XXXXXX"); trap 'rm -rf "$work"' EXIT
src=$work/src; mkdir -p "$src"
git -C "$GHOSTTY_SOURCE" archive "$commit" | tar -x -C "$src"
patch -s -d "$src" -p1 < "$root/patches/legacy-keycap-selectors.patch"
build_slice() { # name zig-target clang-arch sdk clang-target
 local name=$1 target=$2 arch=$3 sdk=$4 triple=$5
 (cd "$src" && "$ZIG_BIN" build -Dversion-string=1.3.2-dev+b58ef1d678be -Demit-lib-vt -Dtarget="$target" -Doptimize=ReleaseFast -Dsimd=false -Dvt-features=-kitty_graphics --prefix "$work/$name")
 xcrun --sdk "$sdk" clang -c -arch "$arch" -target "$triple" -O2 -Wall -Werror -isysroot "$(xcrun --sdk "$sdk" --show-sdk-path)" \
  -I"$work/$name/include" -I"$root/native" "$root/native/shello_vt.c" -o "$work/$name/shello_vt.o"
 xcrun libtool -static -o "$work/$name/libGhosttyVT.a" "$work/$name/lib/libghostty-vt.a" "$work/$name/shello_vt.o"
}
build_slice device aarch64-ios arm64 iphoneos arm64-apple-ios$min
build_slice sim-arm64 aarch64-ios-simulator arm64 iphonesimulator arm64-apple-ios$min-simulator
build_slice sim-x86 x86_64-ios-simulator x86_64 iphonesimulator x86_64-apple-ios$min-simulator
xcrun lipo -create "$work/sim-arm64/libGhosttyVT.a" "$work/sim-x86/libGhosttyVT.a" -output "$work/libGhosttyVT-sim.a"
headers=$work/headers; mkdir -p "$headers"
cp "$root/native/shello_vt.h" "$headers/"
printf 'module GhosttyVT {\n  header "shello_vt.h"\n  export *\n}\n' > "$headers/module.modulemap"
out="$root/ios/Frameworks/parts/GhosttyVT.xcframework"; rm -rf "$out"; mkdir -p "$root/ios/Frameworks/parts"
xcrun xcodebuild -create-xcframework -library "$work/device/libGhosttyVT.a" -headers "$headers" -library "$work/libGhosttyVT-sim.a" -headers "$headers" -output "$out"
echo "Built $out"
