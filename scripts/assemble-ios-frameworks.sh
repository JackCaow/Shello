#!/usr/bin/env bash
# Merges parts/GhosttyVT.xcframework and parts/SSH2.xcframework into one ShelloNative.xcframework
# (two Clang modules, one modulemap) so SwiftPM has a single binary target.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd); fw=$root/ios/Frameworks; parts=$fw/parts
work=$(mktemp -d "${TMPDIR:-/tmp}/shello-fw.XXXXXX"); trap 'rm -rf "$work"' EXIT
lib() { # framework identifier -> path of the static library inside that slice
 /usr/libexec/PlistBuddy -c "Print :AvailableLibraries" "$parts/$1.xcframework/Info.plist" | awk -v id="$2" '/LibraryPath/ {p=$3} /LibraryIdentifier/ {if ($3==id) {print p; exit}}'
}
slice() { # identifier
 xcrun libtool -static -o "$work/$1.a" "$parts/GhosttyVT.xcframework/$1/$(lib GhosttyVT "$1")" "$parts/SSH2.xcframework/$1/$(lib SSH2 "$1")"
}
ids() { /usr/libexec/PlistBuddy -c "Print :AvailableLibraries" "$parts/$1.xcframework/Info.plist" | grep LibraryIdentifier | awk '{print $3}'; }
mkdir -p "$work/headers"
cp "$parts/GhosttyVT.xcframework/$(ids GhosttyVT | head -1)/Headers/shello_vt.h" "$work/headers/"
for h in libssh2.h libssh2_sftp.h libssh2_publickey.h; do cp "$parts/SSH2.xcframework/$(ids SSH2 | head -1)/Headers/$h" "$work/headers/"; done
cat > "$work/headers/module.modulemap" <<'MM'
module GhosttyVT {
  header "shello_vt.h"
  export *
}
module CSSH2 {
  header "libssh2.h"
  header "libssh2_sftp.h"
  export *
}
MM
args=()
for id in $(ids GhosttyVT); do slice "$id"; args+=(-library "$work/$id.a" -headers "$work/headers"); done
rm -rf "$fw/ShelloNative.xcframework"
xcrun xcodebuild -create-xcframework "${args[@]}" -output "$fw/ShelloNative.xcframework"
echo "Built $fw/ShelloNative.xcframework"
