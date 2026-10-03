import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.WatchUi;

// What only the pages draw, kept out of the glance's memory (Draw is what
// the two share): the round sub-window (top right on the Solar, beside the
// START button), used for one thing at a time -- a gauge, a count, or what
// START does -- white with black inside, as the watch's own sub-window is;
// the page indicator; and fitting text to the round screen.
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
        // In whole degrees. An arc from 90 to 90 is the whole circle, so
        // under a degree is drawn as nothing left -- the thin ring alone, as
        // for no reading (share -1); a gap of two degrees or less is not one
        // at a 4px pen, so that is drawn as full.
        var sweep = (share * 360).toNumber();
        if (sweep <= 0) {
            dc.drawCircle(cx, cy, r);
            return;
        }
        dc.setPenWidth(4);
        if (sweep >= 358) {
            dc.drawCircle(cx, cy, r);
            dc.setPenWidth(1);
            return;
        }
        var end = norm(90 - sweep);
        dc.drawArc(cx, cy, r, Graphics.ARC_CLOCKWISE, 90, end);
        dc.setPenWidth(1);
        if (sweep + 2 * gap < 360) {
            dc.drawArc(cx, cy, r, Graphics.ARC_CLOCKWISE, norm(end - gap), norm(90 + gap));
        }
    }

    var roundEdge as Boolean? = null;  // the screen's left edge is curved

    // The page indicator: a mark per page down the middle of the left edge,
    // top to bottom, page [page]'s the one that stands out -- an arc of
    // segments round the edge on a round or semi-round screen, a column of
    // boxes straight down it where the edge is flat (the Instinct 3
    // Solar's). Its sizes follow the screen, as they are on a 176-pixel one.
    function indicator(dc as Graphics.Dc, page as Number, n as Number) as Void {
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var half = cx < cy ? cx : cy;
        if (roundEdge == null) {
            var shape = System.getDeviceSettings().screenShape;
            roundEdge = shape == System.SCREEN_SHAPE_ROUND || shape == System.SCREEN_SHAPE_SEMI_ROUND;
        }
        if (roundEdge == true) {
            arcIndicator(dc, cx, cy, half, page, n);
        } else {
            barIndicator(dc, cy, half, page, n);
        }
    }

    // Round the edge: segments of 9 degrees with 3 between, narrower when
    // there are many (6 degrees at the most there are: ten, Pages.count),
    // so the arc keeps to 90 degrees of the left edge, clear of the title.
    function arcIndicator(dc as Graphics.Dc, cx as Number, cy as Number, half as Number, page as Number, n as Number) as Void {
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

    // Straight down a flat edge, as the watch's own page loops draw it: a
    // small box per page at the very edge, page [page]'s filled and the
    // others outlined, 7 by 13 pixels with 2 between, over black -- shorter
    // when there are many, so the column keeps to the middle two fifths of
    // the edge, on its flat part and clear of the title.
    function barIndicator(dc as Graphics.Dc, cy as Number, half as Number, page as Number, n as Number) as Void {
        var x = half / 88;
        var w = half * 7 / 88;
        var line = half * 2 / 88;
        var gap = half * 2 / 88;
        var full = half * 13 / 88;
        var box = (half * 4 / 5 - (n - 1) * gap) / n;
        box = box > full ? full : box;
        // A border thin enough that an outlined box still shows its hole
        // when there are many and the boxes are short.
        if (line * 3 > box) {
            line = box / 3 > 0 ? box / 3 : 1;
        }
        var span = n * box + (n - 1) * gap;
        var top = cy - span / 2;
        // Black behind the column, so nothing the page drew shows between.
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(0, top - gap, x + w + gap, span + 2 * gap);
        for (var i = 0; i < n; i++) {
            var y = top + i * (box + gap);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(x, y, w, box);
            if (i != page) {
                dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
                dc.fillRectangle(x + line, y + line, w - 2 * line, box - 2 * line);
            }
        }
    }

    // [degrees] as 0 to 359.
    function norm(degrees as Number) as Number {
        var d = degrees % 360;
        return d < 0 ? d + 360 : d;
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
