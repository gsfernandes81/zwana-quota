"""Draw the sub-window's glyphs pixel by pixel and write garmin/source/Glyphs.mc.

The watch draws each glyph from the rows written here, a pixel for a pixel,
so it looks the same on every build rather than being scaled from shapes at
run time. Run from the repo root after changing a glyph:

    python3 garmin/tools/glyphs.py            # writes garmin/source/Glyphs.mc
    python3 garmin/tools/glyphs.py --preview  # also writes glyphs.png beside it

Each glyph is drawn black on white at 1:1 with PIL (which draws without
anti-aliasing), then read back as rows of `#` and `.`.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 31  # every glyph is drawn on a 31 x 31 grid, centred in the sub-window
C = SIZE // 2
INK = 0
PAPER = 255


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    im = Image.new("L", (SIZE, SIZE), PAPER)
    return im, ImageDraw.Draw(im)


def ring(
    im: Image.Image,
    cx: float,
    cy: float,
    r: float,
    width: float,
    start: float = 0,
    end: float = 360,
) -> None:
    """Ink every pixel whose centre lies in a ring [width] thick inward from radius [r], between
    the angles [start] and [end] (degrees clockwise from 3 o'clock, as the screen's y runs down;
    the range may wrap through 0). Drawn by distance, so the stroke is the same all the way round."""
    px = im.load()
    for y in range(SIZE):
        for x in range(SIZE):
            dist = math.hypot(x - cx, y - cy)
            if not (r - width < dist <= r):
                continue
            a = math.degrees(math.atan2(y - cy, x - cx)) % 360
            inside = start <= a <= end if start <= end else (a >= start or a <= end)
            if inside:
                px[x, y] = INK


def refresh() -> Image.Image:
    # A circular arrow, clockwise: an open ring with its gap at the upper
    # right and a head at the gap's end, pointing on round.
    im, d = canvas()
    r, w = 11.5, 3
    end = 315
    ring(im, C, C, r, w, start=15, end=end)
    # The head, at the ring's end, pointing clockwise (on round, into the
    # gap): a triangle across the ring's line, its base twice the stroke.
    a = math.radians(end)
    mid = r - w / 2
    x, y = C + mid * math.cos(a), C + mid * math.sin(a)
    tx, ty = -math.sin(a), math.cos(a)  # clockwise
    nx, ny = math.cos(a), math.sin(a)  # outward
    d.polygon(
        [
            (x + tx * 6.5, y + ty * 6.5),
            (x + nx * 4.5, y + ny * 4.5),
            (x - nx * 4.5, y - ny * 4.5),
        ],
        fill=INK,
    )
    return im


def power() -> Image.Image:
    # The power symbol: a ring open at the top, and a stroke down into it.
    im, d = canvas()
    ring(im, C, C + 1, 12.4, 3, start=305, end=235)
    d.rectangle([C - 1, C - 13, C + 1, C], fill=INK)
    return im


def laptop(d: ImageDraw.ImageDraw, x0: int, y0: int) -> None:
    # A laptop with its screen's top left at (x0, y0): a screen 18 by 12 in
    # a 2-pixel line, over a base 24 wide.
    d.rectangle([x0 + 3, y0, x0 + 20, y0 + 11], outline=INK, width=2)
    d.rectangle([x0, y0 + 13, x0 + 23, y0 + 15], fill=INK)


def phone(d: ImageDraw.ImageDraw, x0: int, y0: int) -> None:
    # A phone, its top left at (x0, y0): 15 by 21 in a 2-pixel line, its
    # corners rounded, a speaker slot at its foot.
    d.rounded_rectangle([x0, y0, x0 + 14, y0 + 20], radius=3, outline=INK, width=2)
    d.rectangle([x0 + 6, y0 + 16, x0 + 8, y0 + 17], fill=INK)


def badge(d: ImageDraw.ImageDraw, cx: int, cy: int) -> None:
    # Room for a badge: white out to radius 8 round (cx, cy).
    d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=PAPER)


def cross(d: ImageDraw.ImageDraw, cx: int, cy: int) -> None:
    # A cross 10 by 10 in 2-pixel strokes, the same turned either way about
    # the point between its middle pixels.
    for t in range(10):
        d.rectangle([cx - 5 + t, cy - 5 + t, cx - 4 + t, cy - 5 + t], fill=INK)
        d.rectangle([cx + 3 - t, cy - 5 + t, cx + 4 - t, cy - 5 + t], fill=INK)


def star(d: ImageDraw.ImageDraw, cx: int, cy: int) -> None:
    points = []
    for j in range(10):
        rad = 7 if j % 2 == 0 else 3
        a = math.radians(-90 + j * 36)
        points.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    d.polygon(points, fill=INK)


def disconnect() -> Image.Image:
    im, d = canvas()
    laptop(d, 0, 5)
    badge(d, 24, 23)
    cross(d, 24, 23)
    return im


def main_device() -> Image.Image:
    im, d = canvas()
    laptop(d, 0, 5)
    badge(d, 24, 23)
    star(d, 24, 23)
    return im


def main_phone() -> Image.Image:
    im, d = canvas()
    phone(d, 4, 5)
    badge(d, 21, 21)
    star(d, 21, 21)
    return im


def device() -> Image.Image:
    im, d = canvas()
    laptop(d, 3, 7)
    return im


def this_phone() -> Image.Image:
    im, d = canvas()
    phone(d, 8, 5)
    return im


def not_listening() -> Image.Image:
    # The phone struck through, and a question mark beside it.
    im, d = canvas()
    phone(d, 2, 5)
    d.line([(0, 27), (19, 3)], fill=PAPER, width=5)
    d.line([(0, 27), (19, 3)], fill=INK, width=2)
    # A question mark 7 wide, drawn: a hook and a dot.
    qx, qy = 21, 9
    d.arc([qx, qy, qx + 8, qy + 8], start=180, end=90, fill=INK, width=2)
    d.rectangle([qx + 3, qy + 8, qx + 4, qy + 11], fill=INK)
    d.rectangle([qx + 3, qy + 14, qx + 4, qy + 15], fill=INK)
    return im


GLYPHS = {
    "REFRESH": refresh,
    "POWER": power,
    "DISCONNECT": disconnect,
    "MAIN": main_device,
    "MAIN_PHONE": main_phone,
    "DEVICE": device,
    "PHONE": this_phone,
    "NOT_LISTENING": not_listening,
}


def rows(im: Image.Image) -> list[str]:
    px = im.load()
    return [
        "".join("#" if px[x, y] < 128 else "." for x in range(SIZE)) for y in range(SIZE)
    ]


def monkey_c(glyphs: dict[str, list[str]]) -> str:
    out = [
        "import Toybox.Lang;",
        "",
        "// The sub-window's glyphs, a pixel for a pixel: written by",
        "// garmin/tools/glyphs.py, which draws them -- change them there, not here.",
        f"// Each is {SIZE} rows of {SIZE}, `#` for ink; PageDraw.glyph draws one",
        "// centred in the sub-window, the same pixels on every build.",
        "module Glyphs {",
    ]
    for name, lines in glyphs.items():
        out.append(f"    const {name} = [")
        out.extend(f'        "{line}",' for line in lines)
        out.append("    ];")
    out.append("}")
    return "\n".join(out) + "\n"


def main() -> None:
    glyphs = {name: rows(draw()) for name, draw in GLYPHS.items()}
    root = Path(__file__).resolve().parents[1]
    (root / "source" / "Glyphs.mc").write_text(monkey_c(glyphs))
    if "--preview" in sys.argv:
        scale, pad = 8, 4
        sheet = Image.new(
            "RGB",
            (len(glyphs) * (SIZE * scale + pad) + pad, SIZE * scale + 2 * pad),
            (200, 200, 200),
        )
        for i, draw in enumerate(GLYPHS.values()):
            im = draw().convert("RGB").resize((SIZE * scale, SIZE * scale), Image.NEAREST)
            sheet.paste(im, (pad + i * (SIZE * scale + pad), pad))
        sheet.save(Path(__file__).with_name("glyphs.png"))


if __name__ == "__main__":
    main()
