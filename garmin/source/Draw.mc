import Toybox.Graphics;
import Toybox.Lang;

// What the glance draws, and the pages with it, the way the Instinct's own
// screens draw theirs: square ends and right angles, crisp on its MIP
// screen, white on black. Only what the glance uses is here, since the
// glance runs in a few dozen kilobytes and loads all of a (:glance) module;
// what only the pages draw is PageDraw.
//
// Every size is worked out from what it is given; nothing assumes a screen.
(:glance)
module Draw {
    // The Body Battery bar, as the watch draws its own on a MIP screen:
    // square ends and right angles, pixel-crisp. What is left is a thick bar
    // from the left; then a small gap; then what has gone as a thin line to
    // the right end, centred on the thick bar's height. [h] is the thick
    // bar's height; the thin line is 2px (3 on a big screen), the gap 2px.
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

    // A circular arrow, clockwise: an open ring with a head at its gap.
    function resetIcon(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        dc.setPenWidth(r > 5 ? 2 : 1);
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, 45, 345);
        dc.setPenWidth(1);
        var k = 0.7071;
        var px = cx + r * k;
        var py = cy - r * k;
        var a = r * 0.7;
        var b = a * 0.8;
        dc.fillPolygon([
            [(px + a * k).toNumber(), (py + a * k).toNumber()],
            [(px - b * k).toNumber(), (py + b * k).toNumber()],
            [(px + b * k).toNumber(), (py - b * k).toNumber()],
        ]);
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
