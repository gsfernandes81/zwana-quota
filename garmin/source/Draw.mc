import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// The shapes the glance and the pages share, drawn the way the Instinct's
// own screens draw theirs: square ends and right angles, crisp on its MIP
// screen, white on black, and the round sub-window (top right on the Solar, beside the START
// button) used for one thing at a time -- a gauge, a count, or what START
// does -- white with black inside, as the watch's own sub-window is.
//
// Every size is worked out from what it is given; nothing assumes a screen.
(:glance)
module Draw {
    // The Body Battery bar, as the watch draws its own on a MIP screen:
    // square ends and right angles, pixel-crisp. What is left is a thick bar
    // from the left; then a small gap; then what has gone as a thin line to
    // the right end, level with the thick bar's bottom edge. [h] is the thick
    // bar's height; the thin line and the gap are a third of it. [share] is
    // 0 to 1, or below 0 for no reading, drawn as the thin line alone.
    function bar(dc as Graphics.Dc, x as Number, y as Number, w as Number, h as Number, share as Float) as Void {
        var thin = h / 3 > 2 ? h / 3 : 2;
        var gap = h / 3 > 2 ? h / 3 : 2;
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
            dc.fillRectangle(x + from, y + h - thin, w - from, thin);
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

    // A ring gauge, the bar bent round: a thick arc clockwise from twelve
    // o'clock for what is left, a small gap, and a thin arc for what has gone.
    function ring(dc as Graphics.Dc, cx as Number, cy as Number, r as Number, share as Float) as Void {
        var gap = 10;
        dc.setPenWidth(1);
        if (share <= 0.0) {
            dc.drawCircle(cx, cy, r);
            return;
        }
        dc.setPenWidth(4);
        if (share >= 0.995) {
            dc.drawCircle(cx, cy, r);
            dc.setPenWidth(1);
            return;
        }
        var sweep = (share * 360).toNumber();
        var end = norm(90 - sweep);
        dc.drawArc(cx, cy, r, Graphics.ARC_CLOCKWISE, 90, end);
        dc.setPenWidth(1);
        if (sweep + 2 * gap < 360) {
            dc.drawArc(cx, cy, r, Graphics.ARC_CLOCKWISE, norm(end - gap), norm(90 + gap));
        }
    }

    // [degrees] as 0 to 359.
    function norm(degrees as Number) as Number {
        var d = degrees % 360;
        return d < 0 ? d + 360 : d;
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
