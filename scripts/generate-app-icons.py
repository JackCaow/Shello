#!/usr/bin/env python3
"""Resize the approved imagegen masters; export launcher resources and icon bundle.

Requires Pillow. macOS iconutil is optional (adds an ICNS export).
"""
from pathlib import Path
import shutil
import subprocess
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "public/assets/branding/app-icon-v1"
OUTPUT = ROOT / "build/app-icons"
RES = ROOT / "android/sample/src/main/res"
SIZES = (16, 20, 24, 29, 32, 40, 48, 58, 60, 64, 72, 76, 80, 87, 96,
         120, 128, 144, 152, 167, 180, 192, 256, 512, 1024)
DENSITIES = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
             "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}


def png(source, size, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    source.resize((size, size), Image.Resampling.LANCZOS).save(path, optimize=True)


def resource(path, content):
    target = RES / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    packaged = OUTPUT / "android/res" / path
    packaged.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, packaged)


def main():
    opaque = Image.open(ASSETS / "icon-1024.png").convert("RGB")
    foreground = Image.open(ASSETS / "foreground-1024.png").convert("RGBA")
    assert opaque.size == foreground.size == (1024, 1024)
    # Generated outputs only: avoid carrying stale exports into the ZIP.
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)
    for size in SIZES:
        png(opaque, size, OUTPUT / f"png/icon-{size}.png")
    shutil.copy2(ASSETS / "foreground-1024.png", OUTPUT / "foreground-1024.png")
    opaque.save(OUTPUT / "Shello.ico", format="ICO",
                sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    iconset = OUTPUT / "Shello.iconset"
    for size in (16, 32, 128, 256, 512):
        png(opaque, size, iconset / f"icon_{size}x{size}.png")
        png(opaque, size * 2, iconset / f"icon_{size}x{size}@2x.png")
    if shutil.which("iconutil"):
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o",
                        str(OUTPUT / "Shello.icns")], check=True)
    for density, (legacy, adaptive) in DENSITIES.items():
        for name, source, size in (("ic_launcher", opaque, legacy),
                                   ("ic_launcher_foreground", foreground, adaptive)):
            relative = Path(f"mipmap-{density}/{name}.png")
            png(source, size, RES / relative)
            packaged = OUTPUT / "android/res" / relative
            packaged.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(RES / relative, packaged)
    resource(Path("values/ic_launcher_colors.xml"),
             '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
             '    <color name="ic_launcher_background">#0B100E</color>\n</resources>\n')
    # Adaptive launchers crop the 108 dp layer to roughly 72 dp. Inset the mark
    # by 10% per side so the visible shape has the same breathing room as peers.
    inset = ('        <inset android:drawable="@mipmap/ic_launcher_foreground"\n'
             '            android:insetLeft="10%" android:insetTop="10%"\n'
             '            android:insetRight="10%" android:insetBottom="10%"/>\n')
    for version in (26, 33):
        monochrome = (f'    <monochrome>\n{inset}    </monochrome>\n'
                      if version >= 33 else "")
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
               '    <background android:drawable="@color/ic_launcher_background"/>\n'
               f'    <foreground>\n{inset}    </foreground>\n'
               f'{monochrome}</adaptive-icon>\n')
        for name in ("ic_launcher", "ic_launcher_round"):
            resource(Path(f"mipmap-anydpi-v{version}/{name}.xml"), xml)
    shutil.copy2(ASSETS / "README.md", OUTPUT / "README.md")
    archive = OUTPUT.parent / "Shello-icons.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(OUTPUT.rglob("*")):
            if path.is_file():
                bundle.write(path, path.relative_to(OUTPUT))
    print(f"Exported {len(SIZES)} PNG sizes, ICO, iconset, Android resources" +
          (", ICNS" if (OUTPUT / "Shello.icns").exists() else ""))
    print(archive)


if __name__ == "__main__":
    main()
