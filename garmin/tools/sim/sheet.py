"""Lay screenshots side by side at 3x, one bit deep as the Solar lights them.

    python3 garmin/tools/sim/sheet.py DIR OUT.png LABEL=NAME...
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

SCALE, PAD = 3, 12


def main() -> None:
    root, out, items = Path(sys.argv[1]), sys.argv[2], sys.argv[3:]
    tiles = []
    for item in items:
        label, _, name = item.partition("=")
        im = Image.open(root / f"{name or label}.png").convert("RGB")
        px = im.load()
        for y in range(im.height):
            for x in range(im.width):
                r, g, b = px[x, y]
                if r > 150 and g < 90:
                    px[x, y] = (200, 60, 40)
                else:
                    px[x, y] = (232, 238, 240) if (r + g + b) / 3 > 128 else (12, 16, 28)
        tiles.append((label, im.resize((im.width * SCALE, im.height * SCALE), Image.NEAREST)))
    width = sum(t.width for _, t in tiles) + PAD * (len(tiles) + 1)
    height = max(t.height for _, t in tiles) + PAD * 2 + 16
    sheet = Image.new("RGB", (width, height), (245, 245, 245))
    draw = ImageDraw.Draw(sheet)
    x = PAD
    for label, t in tiles:
        draw.text((x, PAD - 2), label, fill=(0, 0, 0))
        sheet.paste(t, (x, PAD + 16))
        x += t.width + PAD
    sheet.save(out)


if __name__ == "__main__":
    main()
