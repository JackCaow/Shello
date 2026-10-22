#!/usr/bin/env python3
"""Render a private Shello QR payload using qrcode + stdlib; no Pillow dependency."""
import argparse
import os
from pathlib import Path
import struct
import zlib

import qrcode


def write_png(payload_file, output_file, ascii_output=False):
    qr = qrcode.QRCode(border=4, box_size=8)
    qr.add_data(payload_file.read_text().strip())
    qr.make(fit=True)
    matrix = qr.get_matrix()
    scale = 8
    width = len(matrix) * scale
    pixels = b''.join((b'\x00' + b''.join(bytes([0 if cell else 255]) * scale for cell in row)) * scale for row in matrix)

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)

    image = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', width, width, 8, 0, 0, 0, 0))
             + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))
    fd = os.open(output_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'wb') as stream:
        os.fchmod(stream.fileno(), 0o600)
        stream.write(image)
    if ascii_output:
        qr.print_ascii(invert=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('payload', type=Path)
    parser.add_argument('image', type=Path)
    parser.add_argument('--ascii', action='store_true')
    options = parser.parse_args()
    write_png(options.payload, options.image, options.ascii)
