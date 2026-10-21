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
cp "$root/companion/pair.sh" "$output/shello-pair.sh"
(cd "$output" && shasum -a 256 "Shello-$version-arm64-v8a.apk" shello-pair.sh > SHA256SUMS)
echo "$output"
