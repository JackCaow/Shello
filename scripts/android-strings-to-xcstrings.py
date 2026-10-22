#!/usr/bin/env python3
"""Convert the Android en / zh-CN string resources into one Apple String Catalog.

Keys are kept identical to Android resource names so both apps share one vocabulary.
Format specifiers become Apple ones (%s -> %@); plurals become plural variations.
Run again after changing Android strings; the Swift code reads keys through `tr(_:)`.
"""
import json, re, sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1] != '-' else ROOT / 'ios/Shello/Resources/Localizable.xcstrings'

def unescape(text):
    text = (text or '').strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    text = text.replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n').replace('\\@', '@').replace('\\?', '?')
    text = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), text)
    return text

def apple(text):
    text = unescape(text)
    text = re.sub(r'%(\d+\$)?s', lambda m: '%' + (m.group(1) or '') + '@', text)
    return text

def load(path):
    out = {}
    for e in ET.parse(path).getroot():
        name = e.attrib['name']
        if e.tag == 'string':
            out[name] = apple(e.text)
        elif e.tag == 'plurals':
            out[name] = {i.attrib['quantity']: apple(i.text) for i in e}
        elif e.tag == 'string-array':
            for i, item in enumerate(e):
                out[f'{name}.{i}'] = apple(item.text)
    return out

catalog = {}
for module in ('sample', 'terminal'):
    res = ROOT / 'android' / module / 'src/main/res'
    for lang, folder in (('en', 'values'), ('zh-Hans', 'values-zh-rCN')):
        for f in sorted((res / folder).glob('*.xml')):
            if f.name in ('ic_launcher_colors.xml', 'ids.xml', 'styles.xml'):
                continue
            try:
                items = load(f)
            except ET.ParseError:
                continue
            for key, value in items.items():
                entry = catalog.setdefault(key, {'extractionState': 'manual', 'localizations': {}})
                if isinstance(value, dict):
                    entry['localizations'][lang] = {'variations': {'plural': {q: {'stringUnit': {'state': 'translated', 'value': v}} for q, v in value.items()}}}
                else:
                    entry['localizations'][lang] = {'stringUnit': {'state': 'translated', 'value': value}}
serialized = json.dumps({'sourceLanguage': 'en', 'strings': dict(sorted(catalog.items())), 'version': '1.0'}, ensure_ascii=False, indent=2) + '\n'
if len(sys.argv) > 1 and sys.argv[1] == '-':
    sys.stdout.write(serialized)
else:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(serialized)
missing = [k for k, v in catalog.items() if set(v['localizations']) != {'en', 'zh-Hans'}]
print(f'{len(catalog)} keys -> {OUT}; keys missing a language: {len(missing)}', file=sys.stderr)
if missing:
    print(missing[:10]); sys.exit(1)
