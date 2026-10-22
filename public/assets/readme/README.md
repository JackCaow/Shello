# README media

- `icon.png`: 192 × 192 export of the existing approved app icon.
- `preview.gif`: 960 × 640, five screenshot slides, 3 seconds per slide, looping.
- `preview.png`: static fallback for the first slide.

This is a screenshot slideshow, not a recording of a continuous interaction. Screenshots come from the repository's existing validation evidence; some show earlier development builds. No QR payloads, private keys or personal session transcripts are included.

Source images are listed in `generate-preview.py`: Claude Code color preview, Vim visual selection, empty SSH key form, Shello theme and toolbar settings. Original screenshots are kept unchanged. The icon source is `public/assets/branding/app-icon-v1/icon-1024.png`.

Regenerate with Python and Pillow:

```sh
python3 public/assets/readme/generate-preview.py
# On another platform, if needed:
python3 public/assets/readme/generate-preview.py --font /path/to/font.ttf
```
