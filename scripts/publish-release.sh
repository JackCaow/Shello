#!/usr/bin/env bash
# Called only after signed artifacts have passed the release job.
set -euo pipefail
: "${RELEASE_TAG:?}" "${GH_REPO:?}"
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || exit 1
version=${RELEASE_TAG#v}
assets=${1:?Pass artifact directory}
# Never replace assets in an already public release during a rerun.
releases=$(gh api "repos/$GH_REPO/releases" --paginate --jq '.[] | [.tag_name, .draft] | @tsv')
existing=$(printf '%s\n' "$releases" | awk -F '\t' -v tag="$RELEASE_TAG" '$1==tag {print $2}')
if [[ "$existing" == false ]]; then
    echo "Release $RELEASE_TAG is already published; leaving its assets unchanged."
    exit 0
fi
(cd "$assets" && shasum -a 256 -c SHA256SUMS)
notes=$(mktemp)
trap 'rm -f "$notes"' EXIT
cat > "$notes" <<EOF_NOTES
Shello $version — Android 8.1+ (API 27), arm64-v8a.

Signed release APK, standalone pairing launcher, SHA-256 checksums and certificate details are attached.

Pair on an SSH-enabled Mac/Linux host with Python 3.9+, OpenSSL and tmux:

\`\`\`sh
curl -fsSL https://raw.githubusercontent.com/$GH_REPO/$RELEASE_TAG/companion/pair.sh | bash
\`\`\`

Then select **+ → Pair with QR code** in Shello. Release APKs use the same signing certificate as v0.1.0-alpha02. Earlier debug-signed installations cannot be overwritten; uninstalling them removes saved connection data.

No Maven packages are published. See the source commit history for changes.
EOF_NOTES
if [[ "$existing" != true ]]; then
    args=(--verify-tag --draft --title "Shello $RELEASE_TAG" --notes-file "$notes")
    if [[ "$version" == *-* ]]; then args+=(--prerelease); fi
    gh release create "$RELEASE_TAG" "${args[@]}"
fi
gh release upload "$RELEASE_TAG" --clobber "$assets/Shello-$version-arm64-v8a.apk" "$assets/shello-pair.sh" "$assets/SHA256SUMS" "$assets/signature.txt"
gh release edit "$RELEASE_TAG" --draft=false
