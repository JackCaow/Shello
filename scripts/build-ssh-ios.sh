#!/usr/bin/env bash
# Builds ios/Frameworks/parts/SSH2.xcframework (libssh2 + OpenSSL, static) for device and simulator.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${SSH_SOURCES:=$root/.native/ssh-sources}"
openssl_v=3.4.1; libssh2_v=1.11.1; min=16.0
openssl_sha=002a2d6b30b58bf4bea46c43bdd96365aaf8daa6c428782aa4feee06da197df3
libssh2_sha=d9ec76cbe34db98eec3539fe2c899d26b0c837cb3eb466a56b0f109cabf658f7
mkdir -p "$SSH_SOURCES"
fetch() { # file url sha
 [[ -f "$SSH_SOURCES/$1" ]] || curl -fsSL -o "$SSH_SOURCES/$1" "$2"
 [[ $(shasum -a 256 "$SSH_SOURCES/$1" | cut -d' ' -f1) == "$3" ]] || { echo "Checksum mismatch: $1" >&2; exit 1; }
}
fetch openssl.tar.gz "https://github.com/openssl/openssl/releases/download/openssl-$openssl_v/openssl-$openssl_v.tar.gz" $openssl_sha
fetch libssh2.tar.gz "https://github.com/libssh2/libssh2/releases/download/libssh2-$libssh2_v/libssh2-$libssh2_v.tar.gz" $libssh2_sha
work=$root/.native/ssh-build; mkdir -p "$work"  # OpenSSL slices are cached here; delete to rebuild
build_slice() { # name sdk arch triple openssl-target
 local name=$1 sdk=$2 arch=$3 triple=$4 target=$5 prefix="$work/$1" sysroot
 sysroot=$(xcrun --sdk "$sdk" --show-sdk-path)
 if [[ ! -f "$prefix/lib/libssl.a" ]]; then
 rm -rf "$work/src-$name"; mkdir -p "$work/src-$name" && tar -xzf "$SSH_SOURCES/openssl.tar.gz" -C "$work/src-$name" --strip-components=1
 (cd "$work/src-$name" && CC="$(xcrun --sdk "$sdk" -f clang) -target $triple -isysroot $sysroot" ./Configure "$target" no-shared no-tests no-apps no-docs no-asm no-engine no-dso no-ui-console no-comp --prefix="$prefix" --openssldir="$prefix/ssl" >/dev/null && make -j8 build_libs >/dev/null && make install_dev >/dev/null)
 fi
 rm -rf "$work/ssh2-$name"
 mkdir -p "$work/ssh2-$name" && tar -xzf "$SSH_SOURCES/libssh2.tar.gz" -C "$work/ssh2-$name" --strip-components=1
 cmake -S "$work/ssh2-$name" -B "$work/ssh2-$name/b" -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES="$arch" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=$min -DCMAKE_C_FLAGS="-target $triple" -DCMAKE_INSTALL_PREFIX="$prefix" -DBUILD_SHARED_LIBS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF \
  -DCRYPTO_BACKEND=OpenSSL -DOPENSSL_ROOT_DIR="$prefix" -DOPENSSL_INCLUDE_DIR="$prefix/include" -DOPENSSL_CRYPTO_LIBRARY="$prefix/lib/libcrypto.a" -DOPENSSL_SSL_LIBRARY="$prefix/lib/libssl.a" -DOPENSSL_USE_STATIC_LIBS=ON -DENABLE_ZLIB_COMPRESSION=OFF -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY >/dev/null
 cmake --build "$work/ssh2-$name/b" -j8 >/dev/null && cmake --install "$work/ssh2-$name/b" >/dev/null
 xcrun libtool -static -o "$prefix/libSSH2.a" "$prefix/lib/libssh2.a" "$prefix/lib/libssl.a" "$prefix/lib/libcrypto.a" 2>/dev/null
}
build_slice device iphoneos arm64 arm64-apple-ios$min ios64-xcrun
build_slice sim-arm64 iphonesimulator arm64 arm64-apple-ios$min-simulator darwin64-arm64-cc
build_slice sim-x86 iphonesimulator x86_64 x86_64-apple-ios$min-simulator darwin64-x86_64-cc
xcrun lipo -create "$work/sim-arm64/libSSH2.a" "$work/sim-x86/libSSH2.a" -output "$work/libSSH2-sim.a"
headers=$work/headers; mkdir -p "$headers"
cp "$work/device/include/libssh2.h" "$work/device/include/libssh2_sftp.h" "$work/device/include/libssh2_publickey.h" "$headers/"
printf 'module CSSH2 {\n  header "libssh2.h"\n  header "libssh2_sftp.h"\n  export *\n}\n' > "$headers/module.modulemap"
out="$root/ios/Frameworks/parts/SSH2.xcframework"; rm -rf "$out"; mkdir -p "$root/ios/Frameworks/parts"
xcrun xcodebuild -create-xcframework -library "$work/device/libSSH2.a" -headers "$headers" -library "$work/libSSH2-sim.a" -headers "$headers" -output "$out"
echo "Built $out"
