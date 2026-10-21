"""Validate solid TUI colors in unmodified phone screenshots (requires Pillow).

ADB screenshots carry a Display P3 ICC profile on this phone. Convert to sRGB
in memory before comparing VT palette values; allow 2/255 for quantization.
"""
import io
import json
from collections import Counter
from pathlib import Path
from PIL import Image, ImageCms

root = Path(__file__).resolve().parent
cases = {
    'claude-dark-diff': [0x5f0000, 0x005f00, 0x008700],
    'vim-normal': [0x1c1c1c, 0x303030, 0x005f87],
    'vim-incsearch': [0xffff00, 0xffffff, 0x303030],
    'vim-visual-inverse': [0xffff00, 0xffaf5f, 0x5fd7ff],
}
results = []
for name, colors in cases.items():
    image = Image.open(root / (name + '.png'))
    profile = ImageCms.ImageCmsProfile(io.BytesIO(image.info['icc_profile']))
    source_profile = ImageCms.getProfileName(profile).strip()
    image = ImageCms.profileToProfile(image, profile, ImageCms.createProfile('sRGB'), outputMode='RGB')
    # Terminal viewport only: exclude app toolbar, IME and status bar.
    viewport = image.crop((24, 250, 1034, 1320))
    counts = Counter(viewport.get_flattened_data() if hasattr(viewport, "get_flattened_data") else viewport.getdata())
    for color in colors:
        expected = ((color >> 16) & 255, (color >> 8) & 255, color & 255)
        count = sum(n for rgb, n in counts.items() if max(abs(a-b) for a,b in zip(rgb, expected)) <= 2)
        assert count >= 1000, (name, hex(color), count)
        results.append({'screenshot': name + '.png', 'expected_srgb': '#%06x' % color,
                        'matching_pixels': count, 'source_profile': source_profile})
print(json.dumps({'per_channel_tolerance': 2, 'checks': results}, indent=2))
