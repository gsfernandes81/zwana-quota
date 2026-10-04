"""Draw the watch's glyphs pixel by pixel, as bitmap resources.

The watch draws each glyph as one bitmap (PageDraw.bitmap), the same pixels on
every build. They were rows of `#` drawn run by run, and that interpreted
loop was most of what a page cost to draw: the slide between pages showed
two frames. Run from the repo root after changing a glyph:

    python3 garmin/tools/glyphs.py            # writes the PNGs and their XML
    python3 garmin/tools/glyphs.py --preview  # also writes glyphs.png beside it

Each glyph is drawn black on white at 1:1 with PIL (which draws without
anti-aliasing), then written in one colour on transparency:

- the sub-window's, 31 x 31 in black, centred on its white (PageDraw.glyph),
  on the pages and for the device list's focused device (DeviceIcon);
- the Connection page's device icons in white, cropped to their ink: the
  sub-window's phone and laptop at full size, and a small pair for when
  more devices than four share the row;
- the dotted rule between the Data page's rows, in white, wider than any
  screen and clipped to the row.

garmin/resources/drawables/glyphs/ holds them at 1:1, for the Solar's 176
pixels; garmin/resources-2x/drawables/ the icons and the rule again at 2:1,
for the AMOLEDs (monkey.jungle gives them that folder, which overrides).
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


def small_laptop() -> Image.Image:
    # 15 by 10: a screen 11 by 7 in a 2-pixel line over a base the full
    # width, a pixel's gap between them.
    im = Image.new("L", (15, 10), PAPER)
    d = ImageDraw.Draw(im)
    d.rectangle([2, 0, 12, 6], outline=INK, width=2)
    d.rectangle([0, 8, 14, 9], fill=INK)
    return im


def small_phone() -> Image.Image:
    # 9 by 12 in a 2-pixel line, its outer corners cut, a speaker dot at its
    # foot.
    im = Image.new("L", (9, 12), PAPER)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 8, 11], outline=INK, width=2)
    for x, y in ((0, 0), (8, 0), (0, 11), (8, 11)):
        d.point((x, y), fill=PAPER)
    d.point((4, 9), fill=INK)
    return im


def ink(draw):
    # [draw]'s glyph cropped to its ink, for icons that stand on a baseline.
    def cropped() -> Image.Image:
        im = draw()
        box = Image.eval(im, lambda v: 255 - v).getbbox()
        return im.crop(box)

    return cropped


def rule() -> Image.Image:
    # Every other pixel, from the first: wider than any screen, clipped.
    im = Image.new("L", (RULE, 1), PAPER)
    for x in range(0, RULE, 2):
        im.putpixel((x, 0), INK)
    return im


RULE = 420

BLACK, WHITE = "000000", "FFFFFF"

# Resource id: (draw, colour of the ink, scaled 2:1 for the AMOLEDs).
GLYPHS = {
    "Refresh": (refresh, BLACK, False),
    "Power": (power, BLACK, False),
    "Disconnect": (disconnect, BLACK, False),
    "Main": (main_device, BLACK, False),
    "MainPhone": (main_phone, BLACK, False),
    "Device": (device, BLACK, False),
    "Phone": (this_phone, BLACK, False),
    "NotListening": (not_listening, BLACK, False),
    # The Connection page's row of devices: full size up to four, small past.
    "PhoneIcon": (ink(this_phone), WHITE, True),
    "LaptopIcon": (ink(device), WHITE, True),
    "PhoneSmall": (small_phone, WHITE, True),
    "LaptopSmall": (small_laptop, WHITE, True),
    "Rule": (rule, WHITE, False),
}
def png(im: Image.Image, colour: str, scale: int, path: Path) -> None:
    """[im]'s ink in [colour] on transparency, each pixel [scale] by [scale]."""
    ink = tuple(int(colour[i : i + 2], 16) for i in (0, 2, 4))
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    px, opx = im.load(), out.load()
    for y in range(im.height):
        for x in range(im.width):
            if px[x, y] < 128:
                opx[x, y] = (*ink, 255)
    if scale > 1:
        out = out.resize((im.width * scale, im.height * scale), Image.NEAREST)
    path.parent.mkdir(parents=True, exist_ok=True)
    out.save(path)


def xml(entries: list[tuple[str, str, str]]) -> str:
    out = [
        "<drawables>",
        "    <!-- Written by garmin/tools/glyphs.py, which draws them: change them",
        "         there, not here. One colour on transparency, never dithered. -->",
    ]
    for rid, filename, colour in entries:
        out.append(f'    <bitmap id="{rid}" filename="{filename}" dithering="none">')
        out.append(f"        <palette><color>{colour}</color></palette>")
        out.append("    </bitmap>")
    out.append("</drawables>")
    return "\n".join(out) + "\n"


def snake(name: str) -> str:
    # MainPhone -> main_phone
    return "".join("_" + c.lower() if c.isupper() else c for c in name).lstrip("_")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    base = root / "resources" / "drawables"
    double = root / "resources-2x" / "drawables"
    entries, doubled = [], []
    for rid, (draw, colour, scaled) in GLYPHS.items():
        name = f"glyphs/{snake(rid)}.png"
        png(draw(), colour, 1, base / name)
        entries.append((rid, name, colour))
        if scaled:
            png(draw(), colour, 2, double / name)
            doubled.append((rid, name, colour))
    (base / "glyphs.xml").write_text(xml(entries))
    (double / "glyphs.xml").write_text(xml(doubled))
    if "--preview" in sys.argv:
        scale, pad = 8, 4
        shown = [draw() for rid, (draw, _, _) in GLYPHS.items() if rid != "Rule"]
        sheet = Image.new(
            "RGB",
            (len(shown) * (SIZE * scale + pad) + pad, SIZE * scale + 2 * pad),
            (200, 200, 200),
        )
        for i, im in enumerate(shown):
            im = im.convert("RGB").resize((im.width * scale, im.height * scale), Image.NEAREST)
            sheet.paste(im, (pad + i * (SIZE * scale + pad), pad))
        sheet.save(Path(__file__).with_name("glyphs.png"))


if __name__ == "__main__":
    main()
