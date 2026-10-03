import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// What only the pages draw, kept out of the glance's memory (Draw is what
// the two share): the round sub-window (top right on the Solar, beside the
// START button), white with a glyph in black -- what START does on the page,
// or what is so where it does nothing -- drawn a pixel for a pixel from
// Glyphs; the page indicator on a screen without one; and fitting text to
// the round screen.
//
// Every size is worked out from what it is given; nothing assumes a screen.
module PageDraw {
    // The sub-window's circle, or null on a watch without one.
    function subscreen() as Array<Number>? {
        var box = WatchUi.getSubscreen();
        if (box == null) {
            return null;
        }
        var b = box as Graphics.BoundingBox;
        var r = (b.width < b.height ? b.width : b.height) / 2;
        return [b.x + b.width / 2, b.y + b.height / 2, r];
    }

    // Sub-window [s], white, ready for a glyph or a word in black.
    function sub(dc as Graphics.Dc, s as Array<Number>) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(s[0], s[1], s[2]);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
    }

    // Glyph [rows] (one of Glyphs', `#` for ink) centred in sub-window
    // [s], a pixel for a pixel: each row's runs of ink as one rectangle.
    function glyph(dc as Graphics.Dc, s as Array<Number>, rows as Array<String>) as Void {
        var n = rows.size();
        var x0 = s[0] - n / 2;
        var y0 = s[1] - n / 2;
        for (var y = 0; y < n; y++) {
            var row = rows[y].toCharArray();
            var run = -1;
            for (var x = 0; x <= row.size(); x++) {
                var ink = x < row.size() && row[x] == '#';
                if (ink && run < 0) {
                    run = x;
                } else if (!ink && run >= 0) {
                    dc.fillRectangle(x0 + run, y0 + y, x - run, 1);
                    run = -1;
                }
            }
        }
    }

    // A word in the middle, where START does nothing: the session's ON or OFF.
    function word(dc as Graphics.Dc, s as Array<Number>, text as String) as Void {
        dc.drawText(s[0], s[1], Graphics.FONT_MEDIUM, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // The page indicator on a screen without a sub-window (the AMOLEDs,
    // which are round), for a moment after each turn: a segment per page
    // round the middle of the left edge, top to bottom,
    // page [page]'s bold, over a black edge of its own so it reads over the
    // page. Segments of 9 degrees with 3 between, narrower when there are
    // many (6 degrees at the most there are: ten, Pages.count), so the arc
    // keeps to 90 degrees of the edge, clear of the title. Its sizes follow
    // the screen, as they are on a 176-pixel one.
    function indicator(dc as Graphics.Dc, page as Number, n as Number) as Void {
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var half = cx < cy ? cx : cy;
        // From the centre across: the left edge, on a semi-round screen too.
        var r = cx - half * 6 / 88;
        var gap = 3;
        var seg = (90 - (n - 1) * gap) / n;
        seg = seg > 9 ? 9 : seg;
        var span = n * seg + (n - 1) * gap;
        var start = 180 - span / 2;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(half * 10 / 88);
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, start - 2, start + span + 2);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var i = 0; i < n; i++) {
            var a = start + i * (seg + gap);
            dc.setPenWidth(half * (i == page ? 6 : 2) / 88);
            dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, a, a + seg);
        }
        dc.setPenWidth(1);
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

    // Where to split [text] in two: just after the '-', '.', '_' or space
    // nearest its middle, else at the middle. Always inside it, so both
    // halves have something; bounded by the text's length.
    function breakAt(text as String) as Number {
        var chars = text.toCharArray();
        var n = chars.size();
        if (n < 2) {
            return n;
        }
        var best = n / 2;
        var off = n;
        for (var i = 1; i < n; i++) {
            if ("-._ ".find(chars[i - 1].toString()) != null) {
                var d = (i - n / 2).abs();
                if (d < off) {
                    best = i;
                    off = d;
                }
            }
        }
        return best;
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

    // [text], shortened with an ellipsis until it is no wider than [width].
    // Bounded by the text's length, so it always ends. Not
    // Graphics.fitTextToArea: that places line breaks, and how it cuts one
    // line -- at a character, or back to the last space, or not at all in a
    // name with none, such as a host name -- is not documented.
    function clip(dc as Graphics.Dc, font as Graphics.FontType, text as String, width as Number) as String {
        if (width <= 0) {
            return "";
        }
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
}
