#!/usr/bin/env bash
# usage: export-ios-screenshots.sh <result.xcresult> <output-dir>   (copies the AllScreens attachments as NN-name.png)
set -euo pipefail
xcresult=${1:?xcresult} out=${2:?output dir}; tmp=$(mktemp -d); mkdir -p "$out"
xcrun xcresulttool export attachments --path "$xcresult" --output-path "$tmp" >/dev/null
python3 - "$tmp" "$out" <<'PY'
import json, shutil, sys, re
from pathlib import Path
tmp, out = Path(sys.argv[1]), Path(sys.argv[2])
count = 0
for test in json.loads((tmp / 'manifest.json').read_text()):
    for a in test['attachments']:
        name = a['suggestedHumanReadableName']
        m = re.match(r'(\d\d-[a-z0-9-]+)_', name)
        if m and a['exportedFileName'].endswith('.png'):
            shutil.copy(tmp / a['exportedFileName'], out / f'{m.group(1)}.png'); count += 1
print(f'{count} screenshots -> {out}')
PY
rm -rf "$tmp"
