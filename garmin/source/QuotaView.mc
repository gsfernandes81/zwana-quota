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
        if (dc has :setAntiAlias) {
            dc.setAntiAlias(true);
        }
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
    function title(dc as Graphics.Dc, text as String, sub as Array<Number>?) as Void {
        var font = Graphics.FONT_XTINY;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (sub != null) {
            var s = sub as Array<Number>;
            var y = s[1] - dc.getFontHeight(font) / 2;
            var right = s[0] - s[2] - 4;
            dc.drawText(right, y, font, text, Graphics.TEXT_JUSTIFY_RIGHT);
        } else {
            dc.drawText(dc.getWidth() / 2, dc.getHeight() / 9, font, text, Graphics.TEXT_JUSTIFY_CENTER);
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
    // stands, or what START does. Nothing when there is nothing to say.
    function footer(dc as Graphics.Dc, text as String?) as Void {
        if (text == null) {
            return;
        }
        var font = Graphics.FONT_XTINY;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() - dc.getFontHeight(font) - dc.getHeight() / 12, font,
            text as String, Graphics.TEXT_JUSTIFY_CENTER);
    }

    function dataLeft(dc as Graphics.Dc, d as Dictionary?, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var share = Quota.left(d);
        title(dc, "DATA LEFT", sub);

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
        y += bh + 4;

        // The bar, and where there is no sub-window, the share beside it.
        var r = (w >= 300) ? 7 : 4;
        var bx = w / 7;
        Draw.bar(dc, bx, y, w - 2 * bx, r, share);
        y += 2 * r + 1 + 6;

        // When the grant lands.
        if (d != null) {
            var font = Graphics.FONT_TINY;
            var at = Quota.clock(Quota.nextReset(d as Dictionary));
            if (sub == null) {
                at = at + "  " + Quota.str(d as Dictionary, "share");
            }
            var fh = dc.getFontHeight(font);
            var ir = fh * 3 / 10;
            var tw = dc.getTextWidthInPixels(at, font);
            var left = (w - tw - 2 * ir - 6) / 2;
            Draw.resetIcon(dc, left + ir, y + fh / 2, ir);
            dc.drawText(left + 2 * ir + 6, y, font, at, Graphics.TEXT_JUSTIFY_LEFT);
        }

        var status = Ask.status();
        if (status == null && d != null) {
            status = Quota.mark(d as Dictionary);
        }
        if (status == null && d == null) {
            status = "open zwana quota on phone";
        }
        if (status == null && Quota.canAsk(d)) {
            status = "START: refresh";
        }
        footer(dc, status);
    }

    function connection(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var on = Quota.str(d, "dat").equals("on");
        var act = Quota.canControl(d) ? Quota.str(d, "act") : "";
        title(dc, "CONNECTION", sub);

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
        }

        var status = Ask.status();
        if (status == null && act.length() > 0) {
            status = "START: " + Quota.str(d, "actl");
        }
        footer(dc, status);
    }

    function devices(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var names = Quota.arr(d, "dn");
        var total = Quota.num(d, "dx");
        title(dc, "DEVICES", sub);

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
        // As many rows as fit above the footer, the last saying how many more.
        var room = (dc.getHeight() - dc.getHeight() / 12 - fh - y) / fh - 1;
        var shown = names.size() < room ? names.size() : room;
        if (shown < names.size() || total > names.size()) {
            shown = (room - 1 < shown) ? room - 1 : shown;
        }
        var x = w / 5;
        for (var i = 0; i < shown; i++) {
            dc.fillCircle(x, y + fh / 2, 2);
            dc.drawText(x + 7, y, font, Quota.item(names, i), Graphics.TEXT_JUSTIFY_LEFT);
            y += fh;
        }
        if (total > shown && shown >= 0 && names.size() > 0) {
            dc.drawText(x + 7, y, font, "+" + (total - shown).toString() + " more", Graphics.TEXT_JUSTIFY_LEFT);
        }

        var status = Ask.status();
        if (status == null && Quota.canControl(d) && QuotaDelegate.removable(d).size() > 0) {
            status = "START: disconnect";
        }
        footer(dc, status);
    }
}
