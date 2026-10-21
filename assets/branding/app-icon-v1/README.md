# Ghostty Android app icon — v1

Original imagegen-generated artwork for this project: a mint-green ghost with a
terminal `>_` face on a dark background. This is project artwork, not the official
Ghostty or Moshi logo.

## Masters

- `icon-1024.png`: opaque 1024 × 1024 RGB master.
- `foreground-1024.png`: transparent 1024 × 1024 RGBA adaptive foreground.

Both masters were resampled from the imagegen output using Lanczos filtering.
The foreground was generated as a separate transparent edit of the approved art.

## Rebuild

With Python 3 and Pillow installed, run from the repository root:

```sh
python3 scripts/generate-app-icons.py
```

This updates the sample Android launcher resources and creates
`build/ghostty-android-icons.zip`. The ZIP contains:

- Opaque PNGs: 16, 20, 24, 29, 32, 40, 48, 58, 60, 64, 72, 76, 80, 87, 96,
  120, 128, 144, 152, 167, 180, 192, 256, 512 and 1024 pixels.
- Transparent 1024-pixel foreground.
- ICO with 16, 24, 32, 48, 64, 128 and 256-pixel representations.
- macOS iconset at 16, 32, 128, 256 and 512 points, at 1× and 2×;
  ICNS is included when macOS `iconutil` is available.
- Ready-to-copy Android `res/` resources.

## Android integration

| Density | Legacy PNG | Adaptive foreground PNG |
| --- | ---: | ---: |
| mdpi | 48 | 108 |
| hdpi | 72 | 162 |
| xhdpi | 96 | 216 |
| xxhdpi | 144 | 324 |
| xxxhdpi | 192 | 432 |

The sample manifest uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
API 26+ resources use separate foreground/background layers; API 33+ also
provides a monochrome layer for themed icons. The background is `#0B100E`.
The foreground's visible bounds stay within the central 66/108 region; launcher
masks determine the final outer shape. See the
[Android adaptive icon specification](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

The export script only resizes/packages the approved artwork. It does not call
imagegen or require an API key. Generated bundles live under ignored `build/`;
the masters and Android resources are versioned.
