#!/usr/bin/env python3
"""Produce a standalone Windows launcher with the exact maintained WSL helpers embedded."""
import base64
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'companion/windows/pair.template.ps1').read_text()
for marker,path in {
    '__PAIR_SH__':'companion/pair.sh',
    '__WSL_HOST__':'companion/windows/wsl-host.sh',
    '__START_PAIRING__':'skills/shello-pair/scripts/start_pairing.py',
    '__RENDER_QR__':'skills/shello-pair/scripts/render_qr.py',
}.items():
    assert source.count(marker)==1
    source=source.replace(marker,base64.b64encode((root/path).read_bytes()).decode())
for output in ('companion/pair.ps1', 'skills/shello-pair/scripts/pair.ps1'):
    (root/output).write_text(source)
