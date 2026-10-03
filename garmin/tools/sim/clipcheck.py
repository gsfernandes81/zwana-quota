"""Flag lit pixels of the Solar's pages that touch the edge of what it shows.

    python3 garmin/tools/sim/clipcheck.py DIR NAME...

Each NAME is a 176 x 176 screenshot DIR/NAME.png (keys.sh shot:). A pixel
drawn past the semi-octagon's cut corners, or under the lens round the
sub-window, is simply not there, and text that loses one reads clipped (the
R of RESET, once). Text drawn inside never touches that edge, so a lit
pixel beside it is drawn too close. Exits 1 if any shot has one.

The corners: cut along 45 degrees, 37 pixels along either edge
(PageDraw.CUT), measured against the device list's white. The sub-window: a
circle of 31 round (144, 31); its own drawing is not checked, the 3 pixels
round it are. Pixels lit the same in every shot (four or more) are the
simulator's own art round the sub-window, not the app's.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image

SIZE, CUT = 176, 37
SX, SY, SR = 144, 31, 31


def seen(x: int, y: int) -> bool:
    if not (0 <= x < SIZE and 0 <= y < SIZE):
        return False
    last = SIZE - 1
    return max(0, y - (last - CUT), CUT - y) <= x <= min(last, last + (last - CUT) - y, last - CUT + y)


def lit(px, x: int, y: int) -> bool:
    r, g, b = px[x, y]
    # The simulator rings the sub-window in red; that is not the app's.
    return (r + g + b) / 3 > 128 and not (r > 150 and g < 90)


def main() -> int:
    root, names = Path(sys.argv[1]), sys.argv[2:]
    shots = {n: Image.open(root / f"{n}.png").convert("RGB").load() for n in names}
    every = all if len(shots) >= 4 else (lambda _: False)
    chrome = {(x, y) for y in range(SIZE) for x in range(SIZE) if every(lit(p, x, y) for p in shots.values())}
    failed = False
    for name, px in shots.items():
        bad = []
        for y in range(SIZE):
            for x in range(SIZE):
                if not lit(px, x, y) or (x, y) in chrome:
                    continue
                d = math.hypot(x - SX, y - SY)
                if d <= SR + 1:
                    continue
                if d <= SR + 4 or not all(seen(x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
                    bad.append((x, y))
        failed |= bool(bad)
        print(f"{name}: " + (f"{len(bad)} pixels at the edge, e.g. {bad[:6]}" if bad else "ok"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
