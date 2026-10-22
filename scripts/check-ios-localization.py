#!/usr/bin/env python3
"""Verify the iOS app's localization: every key used in Swift exists in both languages, format
arguments match between languages, and the catalog is in sync with the Android resources."""
import json, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'ios/Shello/Resources/Localizable.xcstrings'
catalog = json.loads(CATALOG.read_text())['strings']
FORMAT = re.compile(r'%(?:[1-9][0-9]*\$)?[-#+ 0,(]*\d*(?:\.\d+)?(?:ll|l)?[a-zA-Z@]')

def values(entry, lang):
    loc = entry['localizations'][lang]
    return [loc['stringUnit']['value']] if 'stringUnit' in loc else [v['stringUnit']['value'] for v in loc['variations']['plural'].values()]

errors = []
for key, entry in catalog.items():
    for lang in ('en', 'zh-Hans'):
        if lang not in entry['localizations']: errors.append(f'{key}: missing {lang}')
    if set(entry['localizations']) == {'en', 'zh-Hans'}:
        en, zh = values(entry, 'en'), values(entry, 'zh-Hans')
        for a, b in zip(en, zh):
            if sorted(FORMAT.findall(a)) != sorted(FORMAT.findall(b)): errors.append(f'{key}: format arguments differ')

used = set()
for f in list((ROOT / 'ios/Shello').glob('*.swift')):
    text = f.read_text()
    used |= set(re.findall(r'\btr\("([a-z0-9_]+)"', text)) | set(re.findall(r'\btrPlural\("([a-z0-9_]+)"', text)) | set(re.findall(r'\bT\("([a-z0-9_]+)"', text))
    used |= set(re.findall(r'\btr\("(input_[a-z0-9_]+)"', text))
missing = sorted(k for k in used if k not in catalog)
errors += [f'used but not in catalog: {k}' for k in missing]

# catalog must be exactly what the converter generates from the Android resources
out = subprocess.run([sys.executable, str(ROOT / 'scripts/android-strings-to-xcstrings.py'), '-'], capture_output=True, text=True)
if out.returncode != 0:
    errors.append('Android string conversion failed: ' + out.stderr.strip())
else:
    try:
        generated = json.loads(out.stdout)['strings']
        if generated != catalog: errors.append('Localizable.xcstrings is stale; run scripts/android-strings-to-xcstrings.py')
    except (ValueError, KeyError):
        errors.append('Android string conversion did not produce a valid catalog')
print(f'{len(catalog)} keys, {len(used)} used by Swift')
if errors:
    print('\n'.join(errors[:60])); sys.exit(1)
print('iOS localization OK')
