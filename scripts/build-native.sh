#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ZIG_BIN:=zig}"
: "${GHOSTTY_SOURCE:=$root/.native/ghostty}"
commit=b58ef1d678be2747c830bc1b1e6c0a086afecf9a
[[ $("$ZIG_BIN" version) == 0.16.0 ]] || { echo 'Zig 0.16.0 required' >&2; exit 1; }
if [[ ! -d "$GHOSTTY_SOURCE" ]]; then
 mkdir -p "$GHOSTTY_SOURCE"
 git -C "$GHOSTTY_SOURCE" init -q
 git -C "$GHOSTTY_SOURCE" fetch --depth 1 https://github.com/ghostty-org/ghostty.git "$commit"
 git -C "$GHOSTTY_SOURCE" checkout --detach FETCH_HEAD
fi
[[ $(git -C "$GHOSTTY_SOURCE" rev-parse HEAD) == "$commit" ]] || { echo "Expected Ghostty $commit" >&2; exit 1; }
case "$(uname -s)-$(uname -m)" in
 Darwin-arm64|Darwin-x86_64) host=darwin-x86_64 ;;
 Linux-x86_64) host=linux-x86_64 ;;
 *) echo 'macOS or Linux x86_64 build host required' >&2; exit 1 ;;
esac
ndk="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin"
[[ -x "$ndk/aarch64-linux-android27-clang" ]] || { echo 'Install Android NDK 28.2.13676358' >&2; exit 1; }
mkdir -p "$root/.native"
patched=$(mktemp -d "$root/.native/source.XXXXXX")
trap 'rm -rf "$patched"' EXIT
git -C "$GHOSTTY_SOURCE" archive "$commit" | tar -x -C "$patched"
patch -s -d "$patched" -p1 < "$root/patches/legacy-keycap-selectors.patch"
(cd "$patched" && "$ZIG_BIN" build -Demit-lib-vt -Dtarget=aarch64-linux-android.27 -Doptimize=ReleaseFast -Dsimd=false -Dvt-features=-kitty_graphics --prefix "$root/.native/install")
output="$root/terminal/src/main/jniLibs/arm64-v8a/libghostty_android.so"
mkdir -p "$(dirname "$output")"
"$ndk/aarch64-linux-android27-clang" -shared -fPIC -O2 -Wl,--no-undefined -Wl,-z,max-page-size=16384 -I"$patched/include" \
 "$root/terminal/src/main/cpp/ghostty_android.c" "$root/.native/install/lib/libghostty-vt.a" -lm -o "$output"
if "$ndk/llvm-nm" "$output" | grep -E ' [tTwW] (calloc|free)$'; then echo 'Unexpected hidden allocator stub' >&2; exit 1; fi
printf 'Built %s\n' "$output"
