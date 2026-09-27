import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// The shapes the glance and the pages share, drawn the way the Instinct's
// own screens draw theirs: rounded ends on every bar and ring, white on
// black, and the round sub-window (top right on the Solar, beside the START
// button) used for one thing at a time -- a gauge, a count, or what START
// does -- white with black inside, as the watch's own sub-window is.
//
// Every size is worked out from what it is given; nothing assumes a screen.
(:glance)
module Draw {
    // The Body Battery bar: what is left as a thick bar from the left, what
    // has gone as a thin line after it, both with round ends, and the thin
    // line starting under the thick one's end so there is no notch between
    // them. [r] is the thick bar's radius: it is 2r+1 pixels tall. [share]
    // is 0 to 1, or below 0 for no reading, drawn as the thin line alone.
    function bar(dc as Graphics.Dc, x as Number, y as Number, w as Number, r as Number, share as Float) as Void {
        var cy = y + r;
        var t = (r >= 4) ? 1 : 0;           // the thin line's radius: 3px, or 1px on a small bar
        var fill = 0;
        if (share > 0.0) {
            fill = (share * w).toNumber();
            if (fill < 2 * r + 1) {
                fill = 2 * r + 1;
            }
            if (fill > w) {
                fill = w;
            }
        }
        // The thin line, from under the thick bar's round end to its own.
        var from = (fill > 0) ? x + fill - r - 1 : x + t;
        var to = x + w - t - 1;
        if (to > from) {
            dc.fillRectangle(from, cy - t, to - from + 1, 2 * t + 1);
            if (t > 0) {
                dc.fillCircle(to, cy, t);
                if (fill == 0) {
                    dc.fillCircle(from, cy, t);
                }
            }
        }
        // The thick bar: a rectangle between two round ends.
        if (fill > 0) {
            dc.fillCircle(x + r, cy, r);
            dc.fillCircle(x + fill - r - 1, cy, r);
            if (fill - 2 * r - 1 > 0) {
                dc.fillRectangle(x + r, y, fill - 2 * r - 1, 2 * r + 1);
            }
        }
    }

    // The sub-window's circle, or null on a watch without one.
    function subscreen() as Array<Number>? {
        if (!(WatchUi has :getSubscreen)) {
            return null;
        }
        var box = WatchUi.getSubscreen();
        if (box == null) {
            return null;
        }
        var b = box as Graphics.BoundingBox;
        var r = (b.width < b.height ? b.width : b.height) / 2;
        return [b.x + b.width / 2, b.y + b.height / 2, r];
    }

    // Fill the sub-window white, ready for something black inside it.
    function subBackground(dc as Graphics.Dc, sub as Array<Number>) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(sub[0], sub[1], sub[2]);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
    }

    // A ring gauge, the bar bent round: a thin full circle, and over it a
    // thick arc from twelve o'clock, clockwise, for what is left.
    function ring(dc as Graphics.Dc, cx as Number, cy as Number, r as Number, share as Float) as Void {
        dc.setPenWidth(1);
        dc.drawCircle(cx, cy, r);
        if (share <= 0.0) {
            return;
        }
        dc.setPenWidth(4);
        if (share >= 0.995) {
            dc.drawCircle(cx, cy, r);
        } else {
            var end = 90 - (share * 360).toNumber();
            if (end < 0) {
                end += 360;
            }
            dc.drawArc(cx, cy, r, Graphics.ARC_CLOCKWISE, 90, end);
        }
        dc.setPenWidth(1);
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

    // The power symbol: a ring open at the top, and a stroke down into it.
    function powerIcon(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var pen = (r > 8) ? 3 : 2;
        dc.setPenWidth(pen);
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, 125, 55);
        dc.drawLine(cx, cy - r - pen / 2, cx, cy);
        dc.setPenWidth(1);
    }

    // Page dots down the left edge, the current one filled, sized to the
    // screen: radius 3, 10px apart on the Solar.
    function pageDots(dc as Graphics.Dc, page as Number, count as Number) as Void {
        if (count < 2) {
            return;
        }
        var h = dc.getHeight();
        var r = h / 60 > 3 ? h / 60 : 3;
        var gap = 3 * r + 1;
        var y = h / 2 - (count - 1) * gap / 2;
        var x = 2 * r + 2;
        for (var i = 0; i < count; i++) {
            if (i == page) {
                dc.fillCircle(x, y + i * gap, r);
            } else {
                dc.drawCircle(x, y + i * gap, r);
            }
        }
    }

    // How wide the round screen is across a text row from [y] to
    // [y] + [height], less a margin: the narrower of the row's two edges.
    function chord(dc as Graphics.Dc, y as Number, height as Number) as Number {
        var r = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var dy = (y - cy).abs();
        var dy2 = (y + height - cy).abs();
        if (dy2 > dy) {
            dy = dy2;
        }
        if (dy >= r) {
            return 0;
        }
        return 2 * Math.sqrt(r * r - dy * dy).toNumber() - 8;
    }

    // [text], shortened with an ellipsis until it is no wider than [width].
    // Bounded by the text's length, so it always ends.
    function clip(dc as Graphics.Dc, font as Graphics.FontType, text as String, width as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= width) {
            return text;
        }
        for (var n = text.length() - 1; n > 0; n--) {
            var cut = (text.substring(0, n) as String) + "...";
            if (dc.getTextWidthInPixels(cut, font) <= width) {
                return cut;
            }
        }
        return "";
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

    // Digits, and the point and separator a figure can hold, and nothing else:
    // what may go in a number font, which may have little else.
    function numeric(text as String) as Boolean {
        var chars = text.toCharArray();
        if (chars.size() == 0) {
            return false;
        }
        for (var i = 0; i < chars.size(); i++) {
            if ("0123456789.,".find(chars[i].toString()) == null) {
                return false;
            }
        }
        return true;
    }
}
