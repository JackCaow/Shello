#!/usr/bin/env bash
# Private acceptance instrumentation only. Never included in the application or release assets.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
signing="${SHELLO_SIGNING_DIR:-$HOME/.local/share/shello/signing}"
out="$root/build/stable-release/probe"
mkdir -p "$out/classes" "$out/dex"
"$sdk/build-tools/35.0.0/aapt2" link --manifest "$root/scripts/release-probe/AndroidManifest.xml" -I "$sdk/platforms/android-35/android.jar" -o "$out/base.apk"
javac -source 8 -target 8 -classpath "$sdk/platforms/android-35/android.jar" -d "$out/classes" "$root/scripts/release-probe/Probe.java"
"$sdk/build-tools/35.0.0/d8" --min-api 27 --lib "$sdk/platforms/android-35/android.jar" --output "$out/dex" "$out/classes/dev/shello/releaseprobe/Probe.class"
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$sdk/build-tools/35.0.0/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$sdk/build-tools/35.0.0/apksigner" sign --ks "$signing/release.p12" --ks-key-alias shello --ks-pass "file:$signing/password" --out "$out/probe.apk" "$out/aligned.apk"
"$sdk/build-tools/35.0.0/apksigner" verify "$out/probe.apk"
echo 'Private release probe built.'
