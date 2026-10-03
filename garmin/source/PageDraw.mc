import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
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
    // The sub-window's circle, or null on a watch without one. The AMOLED
    // Instinct 3s lack getSubscreen itself, not merely a sub-window: called
    // there unguarded it ends the app as it opens (seen in the simulator).
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

    // Sub-window [s], white, ready for a glyph or a word in black.
    function sub(dc as Graphics.Dc, s as Array<Number>) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(s[0], s[1], s[2]);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
    }

    // Glyph [rows] (one of Glyphs' 31 x 31, `#` for ink) centred in
    // sub-window [s], a pixel for a pixel.
    function glyph(dc as Graphics.Dc, s as Array<Number>, rows as Array<String>) as Void {
        var n = rows.size();
        bits(dc, s[0] - n / 2, s[1] - n / 2, rows, 1);
    }

    // [rows] (`#` for ink) with its top left at ([x], [y]), each pixel [k]
    // by [k]: each row's runs of ink as one rectangle, in the colour set.
    function bits(dc as Graphics.Dc, x0 as Number, y0 as Number, rows as Array<String>, k as Number) as Void {
        for (var y = 0; y < rows.size(); y++) {
            var row = rows[y].toCharArray();
            var run = -1;
            for (var x = 0; x <= row.size(); x++) {
                var ink = x < row.size() && row[x] == '#';
                if (ink && run < 0) {
                    run = x;
                } else if (!ink && run >= 0) {
                    dc.fillRectangle(x0 + run * k, y0 + y * k, (x - run) * k, k);
                    run = -1;
                }
            }
        }
    }

    // A rule from [x0] to [x1] at [y], every other pixel: the screen has no
    // grey, and a solid line reads heavier than a rule between rows should.
    function dotted(dc as Graphics.Dc, x0 as Number, x1 as Number, y as Number) as Void {
        for (var x = x0; x <= x1; x += 2) {
            dc.drawPoint(x, y);
        }
    }

    // How tall [font]'s figures and capitals stand above the line: the lit
    // pixels, not the font's box, whose ascent includes room above them.
    function inkHeight(dc as Graphics.Dc, font as Graphics.FontType) as Number {
        return Graphics.getFontAscent(font) - Graphics.getFontDescent(font);
    }

    // Whether the box from ([x0], [y0]) to ([x1], [y1]) keeps [margin] clear
    // of sub-window [sub] (always, where there is none): the lens's rim
    // covers a little outside the circle reported.
    function clear(sub as Array<Number>?, x0 as Number, y0 as Number, x1 as Number, y1 as Number, margin as Number) as Boolean {
        if (sub == null) {
            return true;
        }
        var s = sub as Array<Number>;
        var dx = s[0] < x0 ? x0 - s[0] : (s[0] > x1 ? s[0] - x1 : 0);
        var dy = s[1] < y0 ? y0 - s[1] : (s[1] > y1 ? s[1] - y1 : 0);
        var r = s[2] + margin;
        return dx * dx + dy * dy > r * r;
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
    // many, so the arc
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

    // The Solar's semi-octagon cuts each corner off along a 45-degree line,
    // 37 pixels along either edge (measured in the simulator, against the
    // device list's white, and held to by its screenshots).
    const CUT = 37;

    // How far across the screen pixels from row [top] to row [bottom] are
    // seen, [margin] in from the edge at the narrower of the two: [left,
    // right], both seen. Round screens by their circle; the semi-octagon by
    // its cut corners. A pixel past either is not drawn, so text must lie
    // within it -- by its ink, not merely its box.
    function edges(dc as Graphics.Dc, top as Number, bottom as Number, margin as Number) as Array<Number> {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var inset = 0;
        if (System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_SEMI_OCTAGON) {
            var cut = CUT * w / 176;
            var a = cut - top;
            var b = bottom - (h - 1 - cut);
            inset = a > b ? a : b;
        } else {
            var r = w / 2;
            var cy = h / 2;
            var dy = (top - cy).abs();
            var dy2 = (bottom - cy).abs();
            dy = dy2 > dy ? dy2 : dy;
            inset = dy >= r ? r : r - Math.sqrt(r * r - dy * dy).toNumber();
        }
        inset = (inset > 0 ? inset : 0) + margin;
        return [inset, w - 1 - inset];
    }

    // How wide the screen is across a text row from [y] to [y] + [height],
    // less a margin, centred: what edges() leaves 4 in from either side.
    function chord(dc as Graphics.Dc, y as Number, height as Number) as Number {
        var e = edges(dc, y, y + height, 4);
        var w = e[1] - e[0] + 1;
        return w > 0 ? w : 0;
    }

    // The rows [font]'s figures and capitals light, drawn centred on [y]
    // (TEXT_JUSTIFY_VCENTER): [top, bottom]. From the baseline up by
    // inkHeight; nothing on the pages hangs below the line.
    function ink(dc as Graphics.Dc, font as Graphics.FontType, y as Number) as Array<Number> {
        var base = y - dc.getFontHeight(font) / 2 + Graphics.getFontAscent(font);
        return [base - inkHeight(dc, font), base];
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
