import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

// Behind the glance, in pages -- UP and DOWN move between them (Pages.mc),
// START does the page's one thing, and the sub-window (top right on the
// Solar, beside START) shows in black on white what START will do there
// (glyph):
//
//   0 Data left    the figure large over a bar of ten, then FREE, PAID and
//                  RESET (the clock time and how long until it); sub-window:
//                  the refresh arrow, or the phone struck through when it is
//                  not listening. START asks for a fresh reading.
//   1 Connection   ON or OFF large, how this phone stands, and a row of the
//                  devices on it; sub-window: the power symbol when START
//                  can switch it, else ON or OFF.
//   2 Devices      the device list (DeviceList.mc); this view only when there
//                  is nothing to list, saying why.
//
// The session pages read `not sent yet` until the phone has sent the
// session (`dat`); START does anything only when the phone said it would
// listen (`ask`, `ctl`). Where there is no sub-window, a line at the
// bottom says what START does instead, where it fits.
//
// Laid out for the Solar's 176 pixels, a pixel for a pixel: every place and
// size below is the Solar's, scaled by the screen's width (at), so on the
// Solar each is the exact pixel it names. Every rectangle, segment and rule
// lands on whole pixels; the screen is one bit deep, so a rule that should
// read lighter than a line is dotted, not grey.
//
// One view per page, made by Pages.view: on opening, and as the pages turn.
class QuotaView extends WatchUi.View {
    // The Solar's width, which every place below is given in.
    const DESIGN = 176;
    // The Connection page's title, longest first: beside the Solar's
    // sub-window only the last fits.
    const CONNECTION = ["CONNECTION", "INTERNET", "ONLINE"];

    var page as Number;

    function initialize(p as Number) {
        View.initialize();
        page = p;
    }

    // [v] of the Solar's pixels on this screen.
    function at(dc as Graphics.Dc, v as Number) as Number {
        return v * dc.getWidth() / DESIGN;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var d = Quota.last();
        var sub = PageDraw.subscreen();
        if (page != 0 && !Quota.hasSession(d)) {
            noSession(dc, sub, page == 1 ? CONNECTION : ["DEVICES"]);
        } else if (page == 1) {
            connection(dc, d as Dictionary, sub);
        } else if (page == Pages.DEVICES) {
            unlisted(dc, d as Dictionary, sub);
        } else {
            dataLeft(dc, d, sub);
        }
        // What START does: the sub-window. Where there is none, where the
        // page is among the pages, for a moment after a turn.
        if (sub != null) {
            var s = sub as Array<Number>;
            PageDraw.sub(dc, s);
            glyph(dc, s, d);
        } else if (Pages.indicating) {
            PageDraw.indicator(dc, page, Pages.COUNT);
        }
    }

    // The sub-window's glyph: what START does here, or, where it does
    // nothing, what is so instead.
    function glyph(dc as Graphics.Dc, s as Array<Number>, d as Dictionary?) as Void {
        if (page != 0 && !Quota.hasSession(d)) {
            PageDraw.word(dc, s, "?");
        } else if (page == 0) {
            if (d == null) {
                // Nothing heard from the phone yet: nothing known about it.
                PageDraw.word(dc, s, "?");
            } else if (Quota.canAsk(d)) {
                PageDraw.glyph(dc, s, Rez.Drawables.Refresh);
            } else {
                PageDraw.glyph(dc, s, Rez.Drawables.NotListening);
            }
        } else if (page == 1) {
            var dd = d as Dictionary;
            if (Quota.canControl(dd) && Quota.str(dd, "act").length() > 0) {
                PageDraw.glyph(dc, s, Rez.Drawables.Power);
            } else {
                PageDraw.word(dc, s, on(dd) ? "ON" : "OFF");
            }
        } else {
            PageDraw.word(dc, s, "0");
        }
    }

    function on(d as Dictionary) as Boolean {
        return Quota.str(d, "dat").equals("on");
    }

    // A session page before the phone has sent the session: say so.
    function noSession(dc as Graphics.Dc, sub as Array<Number>?, titles as Array<String>) as Void {
        title(dc, titles, sub);
        lines(dc, ["not sent yet:", "Read now in the", "phone app"], bodyTop(dc, sub));
    }

    // The Devices page with nothing to list: why not.
    function unlisted(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        title(dc, ["DEVICES"], sub);
        lines(dc, [on(d) ? "none listed" : "data is off"], bodyTop(dc, sub));
    }

    // [text], a line each, centred in the screen below [from].
    function lines(dc as Graphics.Dc, text as Array<String>, from as Number) as Void {
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        var y = (from + dc.getHeight() - fh * text.size()) / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var i = 0; i < text.size(); i++) {
            var t = PageDraw.clip(dc, font, text[i], PageDraw.chord(dc, y, fh));
            dc.drawText(dc.getWidth() / 2, y, font, t, Graphics.TEXT_JUSTIFY_CENTER);
            y += fh;
        }
    }

    // Below the sub-window, or the title where there is none.
    function bodyTop(dc as Graphics.Dc, sub as Array<Number>?) as Number {
        if (sub != null) {
            var s = sub as Array<Number>;
            return s[1] + s[2] + 4;
        }
        var row = titleRow(dc, sub);
        return row[1] + dc.getFontHeight(Graphics.FONT_XTINY);
    }

    // The title's row: its left end and its middle, and the width it has
    // before the sub-window. Beside the sub-window where there is one,
    // from (26, 30); centred near the top where there is not. It ends well
    // short of the sub-window: the lens's rim covers pixels outside the
    // circle getSubscreen() reports, and a title drawn up to that circle
    // lost its last letter under it on the Solar 45 mm.
    function titleRow(dc as Graphics.Dc, sub as Array<Number>?) as Array<Number> {
        if (sub != null) {
            var s = sub as Array<Number>;
            var x = at(dc, 26);
            return [x, at(dc, 30), s[0] - s[2] - s[2] / 2 - 4 - x];
        }
        var fh = dc.getFontHeight(Graphics.FONT_XTINY);
        var y = dc.getHeight() / 9 + fh / 2;
        var w = PageDraw.chord(dc, y - fh / 2, fh);
        return [(dc.getWidth() - w) / 2, y, w];
    }

    // The page's title: the first of [titles], longest first, that fits.
    function title(dc as Graphics.Dc, titles as Array<String>, sub as Array<Number>?) as Void {
        var font = Graphics.FONT_XTINY;
        var row = titleRow(dc, sub);
        var text = Draw.fit(dc, font, titles, row[2]);
        if (text == null) {
            return;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (sub != null) {
            dc.drawText(row[0], row[1], font, text as String, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            dc.drawText(dc.getWidth() / 2, row[1], font, text as String, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // In the title's place, why the reading is doubtful (Quota.mark):
    // inverted, a tab with round ends, so the warning is drawn where the eye
    // starts and nothing below it gives way for it. From (22, 22) to 12 past
    // the text, 38 down; the text 6 in.
    function tab(dc as Graphics.Dc, ladder as Array<String>, sub as Array<Number>?) as Void {
        var font = Graphics.FONT_XTINY;
        var row = titleRow(dc, sub);
        var pad = at(dc, 6);
        var text = Draw.fit(dc, font, ladder, row[2] - 2 * pad + at(dc, 4));
        if (text == null) {
            text = ladder[ladder.size() - 1];
        }
        var tw = dc.getTextWidthInPixels(text as String, font);
        var h = at(dc, 17);
        var x = sub != null ? row[0] - at(dc, 4) : (dc.getWidth() - tw) / 2 - pad;
        var y = row[1] - h / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y, tw + 2 * pad + 1, h, h / 2);
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x + pad, row[1], font, text as String, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
    }

    // On a screen without a sub-window, what START does, centred at the
    // foot of the page where it fits below [below]; nothing if it does not.
    // Where there is a sub-window, its glyph says it, and this draws nothing.
    function hint(dc as Graphics.Dc, sub as Array<Number>?, ladder as Array<String>, below as Number) as Void {
        if (sub != null) {
            return;
        }
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        var y = dc.getHeight() - fh - dc.getHeight() / 12;
        if (y < below) {
            return;
        }
        var text = Draw.fit(dc, font, ladder, PageDraw.chord(dc, y, fh));
        if (text != null) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(dc.getWidth() / 2, y, font, text as String, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    // How far across the page what lights rows [top] to [bottom] may run:
    // [left, right]. The Solar's 16 to 161, kept 2 clear of the screen's
    // edge at those rows: the semi-octagon's cut corners take the ends off
    // the lowest rows, and a round screen's edge off any but the middle.
    function across(dc as Graphics.Dc, top as Number, bottom as Number) as Array<Number> {
        var e = PageDraw.edges(dc, top, bottom, at(dc, 2));
        var left = at(dc, 16);
        var right = at(dc, 161);
        return [e[0] > left ? e[0] : left, e[1] < right ? e[1] : right];
    }

    // How long until [epoch], widest first: 3h 12m, 3h12m, then the whole
    // hours alone, 3h, beside a clock time that carries the minutes; 12m
    // within the hour. Rounded up to the minute, so it never reads 0m while
    // the grant is still ahead.
    function countdown(epoch as Number) as Array<String> {
        var left = epoch - Time.now().value();
        var m = left > 0 ? (left + 59) / 60 : 0;
        if (m < 60) {
            return [m.toString() + "m"];
        }
        var h = (m / 60).toString() + "h";
        var mm = (m % 60).toString() + "m";
        return [h + " " + mm, h + mm, h];
    }

    function dataLeft(dc as Graphics.Dc, d as Dictionary?, sub as Array<Number>?) as Void {
        var mark = (d == null) ? null : Quota.mark(d as Dictionary);
        if (mark != null) {
            tab(dc, mark as Array<String>, sub);
        } else {
            title(dc, ["DATA LEFT", "DATA"], sub);
        }
        figure(dc, Quota.figure(d), sub);

        if (d == null) {
            bar(dc, across(dc, at(dc, 85), at(dc, 93)), -1.0);
            lines(dc, ["open zwana quota", "on the phone"], at(dc, 96));
            return;
        }
        var dd = d as Dictionary;
        // FREE and PAID as the phone spelled them (whole MiB); either absent
        // from a phone app older than its key, and then not drawn. RESET
        // always: the one thing on the page that cannot be inferred from
        // the rest.
        var labels = [] as Array<String>;
        var values = [] as Array<String or Array<String>>;
        var free = Quota.str(dd, "free");
        if (free.length() > 0) {
            labels.add("FREE");
            values.add(free);
        }
        var paid = Quota.str(dd, "paid");
        if (paid.length() > 0) {
            labels.add("PAID");
            values.add(paid);
        }
        var reset = Quota.nextReset(dd);
        labels.add("RESET");
        var parts = [Quota.clock(reset)];
        parts.addAll(countdown(reset));
        values.add(parts);

        // The last row on y = 146, the rest 20 above each other: RESET keeps
        // its place when a row above it is not sent. (The mockup's 152 put
        // the lowest row's ends under the Solar's cut corners: 6 higher, it
        // keeps nearly the whole of 16 to 161.)
        var pitch = at(dc, 20);
        var last = at(dc, 146);
        var y = last - (labels.size() - 1) * pitch;
        // One column for every row and rule, as wide as the lowest row's ink
        // is seen: the labels line up, and none loses a pixel to a corner.
        var lf = PageDraw.ink(dc, Graphics.FONT_XTINY, last);
        var vf = PageDraw.ink(dc, Graphics.FONT_TINY, last);
        var top = PageDraw.ink(dc, Graphics.FONT_TINY, y)[0];
        var column = across(dc, top, lf[1] > vf[1] ? lf[1] : vf[1]);
        bar(dc, column, Quota.left(d));
        var below = y;
        for (var i = 0; i < labels.size(); i++) {
            row(dc, column, y, labels[i], values[i]);
            below = y + pitch / 2;
            if (i < labels.size() - 1) {
                PageDraw.dotted(dc, column[0], column[1], y + at(dc, 10));
            }
            y += pitch;
        }
        if (Quota.canAsk(d)) {
            hint(dc, sub, ["START: refresh", "refresh"], below);
        }
    }

    // The figure: the number in a number font from x = 12, standing on
    // y = 78, its unit in the text font 4 after it, bottoms level -- never
    // under it. A figure too wide to clear the sub-window (with 4 to spare:
    // its lens) steps the number down a size; past that, or a figure that
    // is not a number, it is drawn whole in the text font.
    function figure(dc as Graphics.Dc, figure as String, sub as Array<Number>?) as Void {
        var space = figure.find(" ");
        var number = (space == null) ? figure : figure.substring(0, space) as String;
        var unit = (space == null) ? "" : figure.substring(space + 1, figure.length()) as String;
        var x = at(dc, 12);
        var base = at(dc, 78);
        var right = across(dc, base - PageDraw.inkHeight(dc, Graphics.FONT_NUMBER_MEDIUM), base)[1];
        var uf = Graphics.FONT_TINY;
        var uw = dc.getTextWidthInPixels(unit, uf);
        var gap = at(dc, 4);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (PageDraw.numeric(number)) {
            var fonts = [Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD] as Array<Graphics.FontType>;
            for (var f = 0; f < fonts.size(); f++) {
                var nw = dc.getTextWidthInPixels(number, fonts[f]);
                var top = base - PageDraw.inkHeight(dc, fonts[f]);
                if (x + nw + gap + uw <= right && PageDraw.clear(sub, x, top, x + nw, base, gap)) {
                    dc.drawText(x, base - Graphics.getFontAscent(fonts[f]), fonts[f], number, Graphics.TEXT_JUSTIFY_LEFT);
                    dc.drawText(x + nw + gap, base - Graphics.getFontAscent(uf), uf, unit, Graphics.TEXT_JUSTIFY_LEFT);
                    return;
                }
            }
        }
        var font = Graphics.FONT_MEDIUM;
        var text = PageDraw.clip(dc, font, figure, right - x);
        dc.drawText(x, base - Graphics.getFontAscent(font), font, text, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // The bar: ten segments with 2 between, from y = 85 to 93, across the
    // rows' [column] and 2 further left (13 wide with 2 between, from x = 14
    // to 161, on a column of 16 to 161). Filled, the share of today's pool
    // left, to the nearest tenth -- but never none while anything is left;
    // hollow, a 1-pixel outline. No reading: all hollow.
    function bar(dc as Graphics.Dc, column as Array<Number>, share as Float) as Void {
        var y = at(dc, 85);
        var h = at(dc, 93) - y + 1;
        var gap = at(dc, 2);
        var left = column[0] - at(dc, 2);
        var seg = (column[1] - left + 1 - 9 * gap) / 10;
        left = column[1] + 1 - (10 * seg + 9 * gap);
        var full = 0;
        if (share > 0.0) {
            full = (share * 10.0 + 0.5).toNumber();
            full = full < 1 ? 1 : (full > 10 ? 10 : full);
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(1);
        for (var i = 0; i < 10; i++) {
            var x = left + i * (seg + gap);
            if (i < full) {
                dc.fillRectangle(x, y, seg, h);
            } else {
                dc.drawRectangle(x, y, seg, h);
            }
        }
    }

    // A row centred at [y], across [span]: [label] at the left in the small
    // font, [value] at the right in the larger. A value given as parts is the reset's: the
    // clock time, a dot, and how long until it in the first of its spellings
    // that fits -- and where none does, the clock time alone, which is what
    // must survive.
    function row(dc as Graphics.Dc, span as Array<Number>, y as Number, label as String, value as String or Array<String>) as Void {
        var lf = Graphics.FONT_XTINY;
        var vf = Graphics.FONT_TINY;
        var lw = dc.getTextWidthInPixels(label, lf);
        var room = span[1] - span[0] - lw - at(dc, 6);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(span[0], y, lf, label, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        var right = span[1] + 1;
        if (value instanceof String) {
            var text = PageDraw.clip(dc, vf, value as String, room);
            dc.drawText(right, y, vf, text, Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        // The clock time, then each spelling of the countdown in turn.
        var parts = value as Array<String>;
        var dot = at(dc, 2);
        var pad = at(dc, 3);
        var cw = dc.getTextWidthInPixels(parts[0], vf);
        for (var i = 1; i < parts.size(); i++) {
            var tw = dc.getTextWidthInPixels(parts[i], vf);
            if (cw + pad + dot + pad + tw <= room) {
                dc.drawText(right, y, vf, parts[i], Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
                right -= tw + pad + dot;
                dc.fillRectangle(right, y - dot / 2, dot, dot);
                right -= pad;
                break;
            }
        }
        dc.drawText(right, y, vf, PageDraw.clip(dc, vf, parts[0], room), Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function connection(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var cx = at(dc, 88);
        var isOn = on(d);
        title(dc, CONNECTION, sub);

        // ON or OFF, centred at (88, 90), in the largest font that clears the
        // sub-window.
        var word = isOn ? "ON" : "OFF";
        var cy = at(dc, 90);
        var fonts = wordFonts();
        var font = fonts[fonts.size() - 1];
        for (var f = 0; f < fonts.size(); f++) {
            var half = dc.getTextWidthInPixels(word, fonts[f]) / 2;
            var top = cy - PageDraw.inkHeight(dc, fonts[f]) / 2;
            if (PageDraw.clear(sub, cx - half, top, cx + half, cy + (cy - top), at(dc, 4))) {
                font = fonts[f];
                break;
            }
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy, font, word, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // How this phone stands, as the phone said it; off is off for all.
        var detail = isOn ? Quota.str(d, "dsub") : "for every device";
        if (detail.length() > 0) {
            var df = Graphics.FONT_TINY;
            var dy = at(dc, 117);
            var fh = dc.getFontHeight(df);
            if (dc.getTextWidthInPixels(detail, df) > PageDraw.chord(dc, dy - fh / 2, fh)) {
                df = Graphics.FONT_XTINY;
                fh = dc.getFontHeight(df);
            }
            dc.drawText(w / 2, dy, df, PageDraw.clip(dc, df, detail, PageDraw.chord(dc, dy - fh / 2, fh)),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        var below = icons(dc, d, isOn);
        var act = Quota.canControl(d) ? Quota.str(d, "act") : "";
        if (act.length() > 0) {
            var label = Quota.str(d, "actl");
            hint(dc, sub, ["START: " + label, label], below);
        }
    }

    // The fonts ON and OFF may be drawn in, largest first: a number font only
    // on a watch whose number fonts hold the letters (monkey.jungle says
    // which), where one draws a box for each.
    (:numberWords)
    function wordFonts() as Array<Graphics.FontType> {
        return [Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_LARGE, Graphics.FONT_MEDIUM] as Array<Graphics.FontType>;
    }

    (:textWords)
    function wordFonts() as Array<Graphics.FontType> {
        return [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM] as Array<Graphics.FontType>;
    }

    // The devices on the session as a row of icons standing on y = 156,
    // centred, in the phone's order (this phone first): a phone for a phone,
    // a laptop for anything else. Up to four at full size, 10 apart; more
    // small, 4 apart, and past the most that are sent (eight), +N for the
    // rest -- closer, and then fewer, where the screen's cut corners leave
    // too little room. With none, says so. Returns the y below what it drew.
    function icons(dc as Graphics.Dc, d as Dictionary, isOn as Boolean) as Number {
        var n = isOn ? Pages.devices(d) : 0;
        var font = Graphics.FONT_XTINY;
        if (n == 0) {
            var y = at(dc, 144);
            dc.drawText(dc.getWidth() / 2, y, font, isOn ? "none listed" : "nothing online",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return y + dc.getFontHeight(font) / 2;
        }
        var big = n <= 4;
        var gap = at(dc, big ? 10 : 4);
        var kinds = Quota.arr(d, "dg");
        var row = [] as Array<WatchUi.BitmapResource>;
        var widths = [] as Array<Number>;
        var tall = 0;
        for (var i = 0; i < n; i++) {
            var g = Quota.item(kinds, i);
            var phone = g.equals("phone") || g.equals("mainphone");
            var b = PageDraw.bitmap(big ? (phone ? Rez.Drawables.PhoneIcon : Rez.Drawables.LaptopIcon)
                : (phone ? Rez.Drawables.PhoneSmall : Rez.Drawables.LaptopSmall));
            row.add(b);
            widths.add(b.getWidth());
            tall = b.getHeight() > tall ? b.getHeight() : tall;
        }
        var total = Quota.num(d, "dx");
        var base = at(dc, 156);
        var room = PageDraw.chord(dc, base + 1 - tall, tall);
        // Narrower gaps first, down to 2; then fewer icons, the rest
        // counted in +N. This phone, first, always stays.
        var shown = n;
        var width = 0;
        var more = "";
        while (true) {
            width = 0;
            for (var i = 0; i < shown; i++) {
                width += widths[i] + (i > 0 ? gap : 0);
            }
            more = total > shown ? "+" + (total - shown).toString() : "";
            if (more.length() > 0) {
                width += gap + dc.getTextWidthInPixels(more, font);
            }
            if (width <= room || shown <= 1) {
                break;
            }
            if (gap > at(dc, 2)) {
                gap--;
            } else {
                shown--;
            }
        }
        var x = (dc.getWidth() - width) / 2;
        for (var i = 0; i < shown; i++) {
            dc.drawBitmap(x, base + 1 - row[i].getHeight(), row[i]);
            x += widths[i] + gap;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (more.length() > 0) {
            dc.drawText(x, base + 1 - Graphics.getFontAscent(font), font, more, Graphics.TEXT_JUSTIFY_LEFT);
        }
        return base + 1;
    }
}
