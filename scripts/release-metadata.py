#!/usr/bin/env python3
"""Validate APK version and tag before any signing secrets are materialized."""
import os
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
text = (root / 'android/sample/build.gradle.kts').read_text()
version = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', text).group(1))
if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?', version) or code < 1:
    raise SystemExit('Invalid Android release version')
tag = 'v' + version
if os.environ.get('GITHUB_EVENT_NAME') == 'push':
    if os.environ.get('GITHUB_REF_TYPE') != 'tag' or os.environ.get('GITHUB_REF_NAME') != tag:
        raise SystemExit('Tag must exactly match v<versionName> in android/sample/build.gradle.kts')
values = f'tag={tag}\nversion={version}\nprerelease={str("-" in version).lower()}\n'
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write(values)
print(values, end='')
