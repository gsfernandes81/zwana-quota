import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.WatchUi;

// What only the pages draw, kept out of the glance's memory (Draw is what
// the two share): the round sub-window (top right on the Solar, beside the
// START button), as the watch's own apps draw theirs -- dark, UP's and
// DOWN's arrows at its top and bottom, the page's place in the dots round
// its left side, and in the middle what START does; the page indicator on
// a screen without one; and fitting text to the round screen.
//
// Every size is worked out from what it is given; nothing assumes a screen.
// The sub-window's glyphs are drawn pixel by pixel for the Solar's, about 62
// pixels across, and scale with any other (u).
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

    // [n] pixels of the Solar's sub-window, in sub-window [s].
    function u(s as Array<Number>, n as Number) as Number {
        var v = n * s[2] / 31;
        return v > 0 ? v : 1;
    }

    // Sub-window [s] for page [page] of [n], all but its middle: dark, with a
    // thin ring at its edge; a chevron at the top and one at the bottom, for
    // UP and DOWN; and a dot per page round its left side -- this page's at
    // 9 o'clock, those before it above and those after below, smaller with
    // distance, and any more than three away left out. A dot is a page's
    // place in the order, not round the loop: the first page has none above.
    // Leaves the colour white, for the glyph in the middle.
    function sub(dc as Graphics.Dc, s as Array<Number>, page as Number, n as Number) as Void {
        var cx = s[0];
        var cy = s[1];
        var r = s[2];
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(cx, cy, r);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(1);
        dc.drawCircle(cx, cy, r - 1);
        dc.setPenWidth(2);
        var tip = r - u(s, 7);
        var arm = u(s, 5);
        var rise = u(s, 4);
        dc.drawLine(cx - arm, cy - tip + rise, cx, cy - tip);
        dc.drawLine(cx, cy - tip, cx + arm, cy - tip + rise);
        dc.drawLine(cx - arm, cy + tip - rise, cx, cy + tip);
        dc.drawLine(cx, cy + tip, cx + arm, cy + tip - rise);
        dc.setPenWidth(1);
        var rr = r - u(s, 8);
        for (var i = 0; i < n; i++) {
            var k = i - page;
            var far = k < 0 ? -k : k;
            if (far > 3) {
                continue;
            }
            var a = Math.toRadians(k * 21);
            var x = (cx - rr * Math.cos(a) + 0.5).toNumber();
            var y = (cy + rr * Math.sin(a) + 0.5).toNumber();
            if (far == 0) {
                dc.fillCircle(x, y, u(s, 3));
            } else if (far == 1) {
                dc.fillCircle(x, y, u(s, 2));
            } else {
                var d = u(s, far == 2 ? 3 : 2);
                dc.fillRectangle(x - d / 2, y - d / 2, d, d);
            }
        }
    }

    // START asks for a fresh reading: the circular arrow, clockwise.
    function refreshGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        Draw.resetIcon(dc, s[0] - 1, s[1], u(s, 8));
    }

    // START switches data: the power symbol.
    function powerGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        powerIcon(dc, s[0], s[1] + 1, u(s, 6));
    }

    // A word in the middle, where START does nothing: the session's ON or OFF.
    function wordGlyph(dc as Graphics.Dc, s as Array<Number>, word as String) as Void {
        dc.drawText(s[0], s[1], Graphics.FONT_TINY, word, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // A laptop, its left edge at [x0]: a screen in a 2-pixel line over a base.
    function laptop(dc as Graphics.Dc, s as Array<Number>, x0 as Number, cy as Number) as Void {
        dc.fillRectangle(x0 + u(s, 2), cy - u(s, 7), u(s, 14), u(s, 10));
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(x0 + u(s, 4), cy - u(s, 5), u(s, 10), u(s, 6));
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(x0, cy + u(s, 4), u(s, 18), u(s, 2));
    }

    // A device -- this phone when [mine], else a laptop -- with a badge at
    // its lower right, the two centred together; returns the badge's
    // centre, cleared to black for what goes in it.
    function badged(dc as Graphics.Dc, s as Array<Number>, mine as Boolean) as Array<Number> {
        var bx = s[0];
        var by = s[1] + u(s, 4);
        if (mine) {
            var px = s[0] - u(s, 5);
            phone(dc, s, px);
            bx = px + u(s, 8);
        } else {
            var x0 = s[0] - u(s, 12);
            laptop(dc, s, x0, s[1]);
            bx = x0 + u(s, 20);
        }
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(bx, by, u(s, 7));
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        return [bx, by];
    }

    // START takes this device off: the device, with a cross. The cross is 8
    // wide by 7 tall in two-pixel strokes, the same mirrored left to right
    // and top to bottom: each row's two strokes sit as far either side of
    // the centre line, and row t is row 6 - t turned over.
    function disconnectGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        var b = badged(dc, s, false);
        var h = u(s, 3);
        for (var t = 0; t <= 2 * h; t++) {
            dc.fillRectangle(b[0] - h + t, b[1] - h + t, 2, 1);
            dc.fillRectangle(b[0] + h - t, b[1] - h + t, 2, 1);
        }
    }

    // The device that switched data on, which START cannot take off: the
    // device -- this phone when [mine] -- with a star.
    function mainGlyph(dc as Graphics.Dc, s as Array<Number>, mine as Boolean) as Void {
        var b = badged(dc, s, mine);
        var big = u(s, 9) / 2.0;
        var small = u(s, 2);
        var pts = [] as Array<[Numeric, Numeric]>;
        for (var j = 0; j < 10; j++) {
            var rad = (j % 2 == 0) ? big : small;
            var a = Math.toRadians(-90 + j * 36);
            pts.add([(b[0] + rad * Math.cos(a) + 0.5).toNumber(), (b[1] + rad * Math.sin(a) + 0.5).toNumber()]);
        }
        dc.fillPolygon(pts);
    }

    // A device START does nothing to: the device alone.
    function deviceGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        laptop(dc, s, s[0] - u(s, 9), s[1]);
    }

    // A phone, centred on [cx]: an outline in a 2-pixel line, a speaker
    // slot at its foot.
    function phone(dc as Graphics.Dc, s as Array<Number>, cx as Number) as Void {
        var cy = s[1];
        dc.fillRoundedRectangle(cx - u(s, 5), cy - u(s, 9), u(s, 11), u(s, 19), u(s, 2));
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(cx - u(s, 3), cy - u(s, 7), u(s, 7), u(s, 15), 1);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(cx - u(s, 1), cy + u(s, 6), u(s, 3), 1);
    }

    // This phone, which START cannot take off from here: the phone.
    function phoneGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        phone(dc, s, s[0]);
    }

    // The phone is not listening, so START cannot ask it anything: the
    // phone struck through, and a question mark.
    function notListeningGlyph(dc as Graphics.Dc, s as Array<Number>) as Void {
        var px = s[0] - u(s, 4);
        var cy = s[1];
        phone(dc, s, px);
        var e = u(s, 8);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(4);
        dc.drawLine(px - e, cy + e, px + e, cy - e);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(2);
        dc.drawLine(px - e, cy + e, px + e, cy - e);
        dc.setPenWidth(1);
        dc.drawText(s[0] + u(s, 9), cy, Graphics.FONT_TINY, "?", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
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

    // The power symbol: a ring open at the top, and a stroke down into it.
    function powerIcon(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var pen = (r > 8) ? 3 : 2;
        dc.setPenWidth(pen);
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, 125, 55);
        dc.drawLine(cx, cy - r - pen / 2, cx, cy);
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
