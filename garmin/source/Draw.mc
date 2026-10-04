import Toybox.Graphics;
import Toybox.Lang;

// What the glance draws, and the pages with it, the way the Instinct's own
// screens draw theirs: square ends and right angles, crisp on the Solar's
// MIP screen, white on black. Only what the glance uses is here, since the
// glance runs in a few dozen kilobytes and loads all of a (:glance) module;
// what only the pages draw is PageDraw.
//
// Every size is worked out from what it is given; nothing assumes a screen.
(:glance)
module Draw {
    // The Body Battery bar, as the watch draws its own:
    // square ends and right angles, pixel-crisp. What is left is a thick bar
    // from the left; then a small gap; then what has gone as a thin line to
    // the right end, centred on the thick bar's height. [h] is the thick
    // bar's height; the thin line is 2px (3 on a big screen), the gap the same.
    // [share] is 0 to 1, or below 0 for no reading: the thin line alone.
    function bar(dc as Graphics.Dc, x as Number, y as Number, w as Number, h as Number, share as Float) as Void {
        var thin = h >= 9 ? 3 : 2;
        var gap = h >= 9 ? 3 : 2;
        var fill = 0;
        if (share > 0.0) {
            fill = (share * w).toNumber();
            if (fill < 2) {
                fill = 2;
            }
            if (fill > w) {
                fill = w;
            }
        }
        if (fill > 0) {
            dc.fillRectangle(x, y, fill, h);
        }
        var from = (fill > 0) ? fill + gap : 0;
        if (from < w) {
            dc.fillRectangle(x + from, y + (h - thin) / 2, w - from, thin);
        }
    }

    // A small clock, beside when the grant lands: a thin face, its hands at
    // 12 and 3. Not a circular arrow, which on the pages is START's refresh
    // in the sub-window just above (Rez.Drawables.Refresh).
    function clockIcon(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        dc.setPenWidth(1);
        dc.drawCircle(cx, cy, r);
        dc.drawLine(cx, cy, cx, cy - r + 2);
        dc.drawLine(cx, cy, cx + r - 2, cy);
    }

    // The longest of [ladder] no wider than [width]; null if none is.
    function fit(dc as Graphics.Dc, font as Graphics.FontType, ladder as Array<String>, width as Number) as String? {
        for (var i = 0; i < ladder.size(); i++) {
            if (dc.getTextWidthInPixels(ladder[i], font) <= width) {
                return ladder[i];
            }
        }
        return null;
    }
}
