#!/usr/bin/env python3
"""Assemble real, already-public app screenshots into a README slideshow.
Requires Pillow. No device access, synthetic app screens, or pairing payloads.
"""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
SCENES = [
    ('docs/evidence/tui-colors/claude-dark-diff.png', 'YOUR TERMINAL,\nWITH YOU.', 'Claude Code over SSH', 'Native terminal rendering for your remote tools.'),
    ('docs/evidence/tui-colors/vim-visual-inverse.png', 'KEEP YOUR\nWORKFLOW.', 'Vim & terminal applications', 'Syntax colors, highlights and visual selection.'),
    ('docs/evidence/styled-ui/phone-key-form.png', 'CONNECT\nYOUR WAY.', 'SSH keys & passwords', 'Save connections. Return to your remote workspace.'),
    ('docs/evidence/releases/alpha02/release-theme.png', 'MAKE IT\nYOURS.', 'The Shello theme', 'A dark workspace, with light and black alternatives.'),
    ('docs/evidence/settings-layout/api35-large-toolbar.png', 'BUILT FOR\nYOUR THUMBS.', 'Customizable terminal toolbar', 'Quick keys and touch controls, close at hand.'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, help='A TrueType/OpenType font to use for presentation text')
    args = parser.parse_args()
    choices = [args.font, Path('/System/Library/Fonts/Supplemental/Arial.ttf'), Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')]
    font_path = next((p for p in choices if p and p.is_file()), None)
    if font_path is None:
        parser.error('Supply --font /path/to/font.ttf')
    def font(size): return ImageFont.truetype(str(font_path), size)
    icon = Image.open(ROOT / 'public/assets/branding/app-icon-v1/icon-1024.png').convert('RGB')
    icon.resize((192, 192), Image.Resampling.LANCZOS).save(OUT / 'icon.png', optimize=True)
    frames = []
    for i, (source, title, heading, description) in enumerate(SCENES):
        canvas = Image.new('RGB', (960, 640), '#080d0b')
        draw = ImageDraw.Draw(canvas)
        draw.rounded_rectangle((1, 1, 958, 638), radius=28, outline='#20312a', width=2)
        canvas.paste(icon.resize((56, 56), Image.Resampling.LANCZOS), (42, 38))
        draw.text((111, 46), 'Shello', font=font(30), fill='#f1f8f4')
        draw.text((44, 125), 'ANDROID  /  SSH + TMUX', font=font(15), fill='#45f4a0')
        draw.multiline_text((40, 179), title, font=font(48), fill='#f1f8f4', spacing=9)
        draw.text((44, 334), heading, font=font(23), fill='#45f4a0')
        # Keep captions short enough to remain readable in a GitHub README.
        words = description.split(); line = ''; lines = []
        for word in words:
            test = (line + ' ' + word).strip()
            if draw.textlength(test, font=font(18)) > 475:
                lines.append(line); line = word
            else: line = test
        lines.append(line)
        draw.multiline_text((44, 378), '\n'.join(lines), font=font(18), fill='#a8b9b0', spacing=9)
        for j in range(len(SCENES)):
            x = 44 + j * 27
            draw.rounded_rectangle((x, 536, x + 17, 541), radius=2, fill='#45f4a0' if i == j else '#304239')
        draw.text((44, 574), 'OPEN SOURCE  /  REAL APP SCREENSHOTS', font=font(13), fill='#80968a')
        shot = Image.open(ROOT / source).convert('RGB')
        shot.thumbnail((278, 592), Image.Resampling.LANCZOS)
        x = 634 + (278 - shot.width) // 2; y = (640 - shot.height) // 2
        draw.rounded_rectangle((x-5, y-5, x+shot.width+4, y+shot.height+4), radius=18, fill='#1c2c24')
        canvas.paste(shot, (x, y))
        frames.append(canvas)
    frames[0].save(OUT / 'preview.png', optimize=True)
    frames = [im.quantize(colors=192, method=Image.Quantize.MEDIANCUT) for im in frames]
    frames[0].save(OUT / 'preview.gif', save_all=True, append_images=frames[1:], duration=3000, loop=0, optimize=False, disposal=2)
    print('Created icon.png, preview.png and preview.gif (5 screenshot slides, 15 seconds).')


if __name__ == '__main__':
    main()
