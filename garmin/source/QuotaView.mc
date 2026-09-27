import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Behind the glance, in pages -- UP and DOWN move between them, START does
// the page's one thing, and the sub-window (top right on the Solar, beside
// START) shows each page's one number or what START will do:
//
//   0 Data left    the figure large, the bar, the reset; sub-window: the
//                  share left as a ring. START asks for a fresh reading.
//   1 Connection   ON or OFF, and how this phone stands; sub-window: the
//                  power symbol when START can switch it.
//   2 Devices      who is on; sub-window: how many. START offers the ones
//                  that can be taken off.
//
// Pages 1 and 2 exist only when the phone sent the session (`dat`); START
// does anything only when the phone said it would listen (`ask`, `ctl`).
class QuotaView extends WatchUi.View {
    var page as Number = 0;

    function initialize() {
        View.initialize();
    }

    function pages() as Number {
        return Quota.hasSession(Quota.last()) ? 3 : 1;
    }


    function turn(by as Number) as Void {
        var p = page + by;
        var n = pages();
        if (p >= 0 && p < n) {
            page = p;
            WatchUi.requestUpdate();
        }
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var d = Quota.last();
        if (page >= pages()) {
            page = 0;
        }
        var sub = Draw.subscreen();
        if (page == 1) {
            connection(dc, d as Dictionary, sub);
        } else if (page == 2) {
            devices(dc, d as Dictionary, sub);
        } else {
            dataLeft(dc, d, sub);
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        Draw.pageDots(dc, page, pages());
    }

    // The page's title: beside the sub-window where there is one, centred
    // near the top where there is not.
    // [titles] is the title, longest first: the first that fits is drawn.
    function title(dc as Graphics.Dc, titles as Array<String>, sub as Array<Number>?) as Void {
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (sub != null) {
            var s = sub as Array<Number>;
            var y = s[1] - fh / 2;
            var right = s[0] - s[2] - 5;
            var left = (dc.getWidth() - Draw.chord(dc, y, fh)) / 2;
            var text = Draw.fit(dc, font, titles, right - left);
            if (text != null) {
                dc.drawText(right, y, font, text as String, Graphics.TEXT_JUSTIFY_RIGHT);
            }
        } else {
            dc.drawText(dc.getWidth() / 2, dc.getHeight() / 9, font, titles[0], Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    // Where a page's body starts: below the sub-window, or below the title.
    function top(dc as Graphics.Dc, sub as Array<Number>?) as Number {
        if (sub != null) {
            var s = sub as Array<Number>;
            return s[1] + s[2] + 4;
        }
        return dc.getHeight() / 9 + dc.getFontHeight(Graphics.FONT_XTINY) + 6;
    }

    // One small line at the bottom: why the reading is doubtful, how an ask
    // stands, or what START does -- the first of [ladder] that fits the
    // round screen at that height, and nothing if none does. Never above
    // [below], the bottom of what the page drew.
    function footer(dc as Graphics.Dc, ladder as Array<String>?, below as Number) as Void {
        if (ladder == null) {
            return;
        }
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        var y = dc.getHeight() - fh - dc.getHeight() / 12;
        if (y < below + 1) {
            y = below + 1;
        }
        var text = Draw.fit(dc, font, ladder as Array<String>, Draw.chord(dc, y, fh));
        // Nothing fits down at the narrow bottom: try just under the page's
        // content, where the round screen is wider.
        if (text == null && below + 3 < y) {
            y = below + 3;
            text = Draw.fit(dc, font, ladder as Array<String>, Draw.chord(dc, y, fh));
        }
        if (text == null) {
            return;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, y, font, text as String, Graphics.TEXT_JUSTIFY_CENTER);
    }

    function dataLeft(dc as Graphics.Dc, d as Dictionary?, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var share = Quota.left(d);
        title(dc, ["DATA LEFT", "DATA"], sub);

        // The sub-window: the share left, as the bar bent into a ring.
        if (sub != null) {
            var s = sub as Array<Number>;
            Draw.subBackground(dc, s);
            Draw.ring(dc, s[0], s[1], s[2] - 5, share);
            var pct = (d == null) ? "--" : Quota.str(d, "share");
            dc.drawText(s[0], s[1], Graphics.FONT_XTINY, pct,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        }

        // The figure: the number in the number font, the unit after it.
        var figure = Quota.figure(d);
        var space = figure.find(" ");
        var number = (space == null) ? figure : figure.substring(0, space) as String;
        var unit = (space == null) ? "" : figure.substring(space + 1, figure.length()) as String;
        var big = Graphics.FONT_NUMBER_MEDIUM;
        var small = Graphics.FONT_TINY;
        if (!Draw.numeric(number) || dc.getTextWidthInPixels(number, big) + dc.getTextWidthInPixels(" " + unit, small) > w * 3 / 4) {
            big = Graphics.FONT_MEDIUM;
            number = figure;
            unit = "";
        }
        var y = top(dc, sub);
        var bh = dc.getFontHeight(big);
        var nw = dc.getTextWidthInPixels(number, big);
        var uw = (unit.length() > 0) ? dc.getTextWidthInPixels(" " + unit, small) : 0;
        var x = (w - nw - uw) / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y, big, number, Graphics.TEXT_JUSTIFY_LEFT);
        if (uw > 0) {
            // Bottoms level with the number's.
            dc.drawText(x + nw, y + bh - dc.getFontHeight(small) - bh / 10, small, " " + unit, Graphics.TEXT_JUSTIFY_LEFT);
        }
        y += bh + 2;

        // The bar, and where there is no sub-window, the share beside it.
        var barH = (w >= 300) ? 14 : 8;
        var bx = w / 7;
        Draw.bar(dc, bx, y, w - 2 * bx, barH, share);
        y += barH + 3;

        // When the grant lands.
        if (d != null) {
            var font = Graphics.FONT_TINY;
            var at = Quota.clock(Quota.nextReset(d as Dictionary));
            if (sub == null) {
                at = at + "  " + Quota.str(d as Dictionary, "share");
            }
            var fh = dc.getFontHeight(font);
            var ir = fh * 3 / 10;
            // The arrowhead reaches past the ring by about half its radius.
            var icon = 2 * ir + ir / 2 + 5;
            var tw = dc.getTextWidthInPixels(at, font);
            var left = (w - tw - icon) / 2;
            Draw.resetIcon(dc, left + ir, y + fh / 2, ir);
            dc.drawText(left + icon, y, font, at, Graphics.TEXT_JUSTIFY_LEFT);
            y += fh;
        }

        var status = Ask.status();
        var mark = (d == null) ? null : Quota.mark(d as Dictionary);
        if (status != null) {
            footer(dc, status, y);
        } else if (mark != null) {
            footer(dc, [mark as String], y);
        } else if (d == null) {
            footer(dc, ["open zwana quota on phone", "open on phone"], y);
        } else if (Quota.canAsk(d)) {
            footer(dc, ["START: refresh", "refresh"], y);
        }
    }

    function connection(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var on = Quota.str(d, "dat").equals("on");
        var act = Quota.canControl(d) ? Quota.str(d, "act") : "";
        title(dc, ["CONNECTION", "INTERNET"], sub);

        // The sub-window: the power symbol when START switches, else a
        // filled dot for on and a ring for off.
        if (sub != null) {
            var s = sub as Array<Number>;
            Draw.subBackground(dc, s);
            if (act.length() > 0) {
                Draw.powerIcon(dc, s[0], s[1] + 2, s[2] * 2 / 5);
            } else if (on) {
                dc.fillCircle(s[0], s[1], s[2] / 3);
            } else {
                dc.setPenWidth(3);
                dc.drawCircle(s[0], s[1], s[2] / 3);
                dc.setPenWidth(1);
            }
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        }

        var y = top(dc, sub);
        var big = Graphics.FONT_LARGE;
        dc.drawText(w / 2, y, big, on ? "ON" : "OFF", Graphics.TEXT_JUSTIFY_CENTER);
        y += dc.getFontHeight(big);
        var small = Graphics.FONT_XTINY;
        var detail = Quota.str(d, "dsub");
        if (detail.length() > 0) {
            dc.drawText(w / 2, y, small, detail, Graphics.TEXT_JUSTIFY_CENTER);
            y += dc.getFontHeight(small);
        }
        var n = Quota.num(d, "dx");
        if (on) {
            dc.drawText(w / 2, y, small, n == 1 ? "1 device" : n.toString() + " devices", Graphics.TEXT_JUSTIFY_CENTER);
            y += dc.getFontHeight(small);
        }

        // The sub-window's power symbol is the hint beside START; the words
        // say which way it goes, where they fit.
        var status = Ask.status();
        if (status != null) {
            footer(dc, status, y);
        } else if (act.length() > 0) {
            var label = Quota.str(d, "actl");
            footer(dc, ["START: " + label, label], y);
        }
    }

    function devices(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var names = Quota.arr(d, "dn");
        var total = Quota.num(d, "dx");
        title(dc, ["DEVICES"], sub);

        // The sub-window: how many.
        if (sub != null) {
            var s = sub as Array<Number>;
            Draw.subBackground(dc, s);
            dc.drawText(s[0], s[1], Graphics.FONT_MEDIUM, total.toString(),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        }

        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        var y = top(dc, sub);
        if (names.size() == 0) {
            dc.drawText(w / 2, y, font, Quota.str(d, "dat").equals("on") ? "none listed" : "data is off", Graphics.TEXT_JUSTIFY_CENTER);
        }
        // As many rows as fit above the footer, the last saying how many
        // more when not all do. Each name is cut to the round edge.
        var rows = (dc.getHeight() - dc.getHeight() / 12 - fh - y) / fh;
        var shown = names.size();
        if (total > rows) {
            shown = rows - 1;
        }
        if (shown > names.size()) {
            shown = names.size();
        }
        if (shown < 0) {
            shown = 0;
        }
        var x = w / 5;
        for (var i = 0; i < shown; i++) {
            var room = (w + Draw.chord(dc, y, fh)) / 2 - (x + 7);
            dc.fillCircle(x, y + fh / 2, 2);
            dc.drawText(x + 7, y, font, Draw.clip(dc, font, Quota.item(names, i), room), Graphics.TEXT_JUSTIFY_LEFT);
            y += fh;
        }
        if (total > shown && names.size() > 0 && rows > 0) {
            dc.drawText(x + 7, y, font, "+" + (total - shown).toString() + " more", Graphics.TEXT_JUSTIFY_LEFT);
            y += fh;
        }

        var status = Ask.status();
        if (status != null) {
            footer(dc, status, y);
        } else if (Quota.canControl(d) && QuotaDelegate.removable(d).size() > 0) {
            footer(dc, ["START: disconnect", "disconnect"], y);
        }
    }
}
