#!/usr/bin/env python3
"""Verify resource coverage and format arguments without an Android SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r'%(?:([1-9][0-9]*)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')

def arguments(value):
    return sorted(FORMAT.findall(value or ''))

count = 0
for module in ('sample', 'terminal'):
    resources = ROOT / module / 'src/main/res'
    en = {e.attrib['name']: e for e in ET.parse(resources / 'values/strings.xml').getroot()}
    zh = {e.attrib['name']: e for e in ET.parse(resources / 'values-zh-rCN/strings.xml').getroot()}
    assert en.keys() == zh.keys(), f'{module}: language resource keys differ'
    for name, original in en.items():
        translated = zh[name]
        assert original.tag == translated.tag, name
        if original.tag == 'plurals':
            variants = {e.attrib['quantity']: e.text for e in original}
            assert 'one' in variants and 'other' in variants, name
            expected = arguments(variants['other'])
            for item in list(original) + list(translated):
                assert arguments(item.text) == expected, name
        else:
            assert arguments(original.text) == arguments(translated.text), name
        count += 1
print(f'{count} resource entries: English/Chinese coverage and format arguments match.')
