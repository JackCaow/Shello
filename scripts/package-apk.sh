#!/usr/bin/env bash
# Build and sign with an existing private release key; never publish key material.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
signing_dir="${SHELLO_SIGNING_DIR:-$HOME/.local/share/shello/signing}"
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$android_sdk/build-tools/35.0.0"
for file in "$signing_dir/release.p12" "$signing_dir/password"; do
    [[ -f "$file" ]] || { echo "Missing signing input: $file" >&2; exit 1; }
done
"$root/gradlew" -p "$root" :sample:assembleRelease
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$root/sample/build/outputs/apk/release/output-metadata.json")
output="$root/build/releases/v$version"
mkdir -p "$output"
"$build_tools/zipalign" -f -p 4 "$root/sample/build/outputs/apk/release/sample-release-unsigned.apk" "$output/aligned.apk"
"$build_tools/apksigner" sign --ks "$signing_dir/release.p12" --ks-key-alias shello \
    --ks-pass "file:$signing_dir/password" \
    --out "$output/Shello-$version-arm64-v8a.apk" "$output/aligned.apk"
rm "$output/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$output/Shello-$version-arm64-v8a.apk" > "$output/signature.txt"
# Pin the release certificate so a wrong secret cannot silently break upgrades.
expected=2a28be23a3a37b400c9ba0ad85325b4d8785efc7b396e4bb6b944045d7027ad5
grep -q "certificate SHA-256 digest: $expected" "$output/signature.txt" || { echo 'Unexpected release signing certificate' >&2; exit 1; }
cp "$root/companion/pair.sh" "$output/shello-pair.sh"
cp "$root/companion/pair.ps1" "$output/shello-pair.ps1"
(cd "$output" && shasum -a 256 "Shello-$version-arm64-v8a.apk" shello-pair.sh shello-pair.ps1 > SHA256SUMS)
echo "$output"
