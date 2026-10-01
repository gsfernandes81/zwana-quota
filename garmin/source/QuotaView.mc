import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Behind the glance, in pages -- UP and DOWN move between them (a ViewLoop,
// Pages.mc), START does the page's one thing, and the sub-window (top right
// on the Solar, beside START) shows each page's one number or what START
// will do:
//
//   0 Data left    the figure large, the bar, the reset, how much of it is
//                  paid; sub-window: the share left as a ring. START asks
//                  for a fresh reading.
//   1 Connection   ON or OFF, and how this phone stands; sub-window: the
//                  power symbol when START can switch it.
//   2.. Device     one page per device on the session, this phone first:
//                  its name and how it is on; sub-window: which of how
//                  many. START takes it off, when the phone says it may.
//
// The session pages read `not sent yet` until the phone has sent the
// session (`dat`); START does anything only when the phone said it would
// listen (`ask`, `ctl`).
//
// One view per page, made by the loop as it turns to it.
class QuotaView extends WatchUi.View {
    // The page the loop made this view for. What it draws and what START
    // does is current(), which is this unless the pages have since shrunk.
    var page as Number;

    function initialize(p as Number) {
        View.initialize();
        page = p;
    }

    function onShow() as Void {
        Pages.shown(page);
    }

    // The page this view stands for now: a loop built for more pages than
    // there are, until it is replaced (Pages.refit), has its extra pages
    // shown as the last. Worked out afresh, never stored, so a count that
    // comes back finds the view on its own page again.
    function current() as Number {
        return Pages.clamp(page, Pages.count(Quota.last()));
    }

    // The IP START may ask the phone to take off from device page [i], or ""
    // when there is none: the phone sends one only for a device it will
    // take off, and only when it lets the watch switch (`ctl`).
    function deviceIp(d as Dictionary?, i as Number) as String {
        if (!Quota.canControl(d) || i < 0 || i >= Pages.devices(d as Dictionary)) {
            return "";
        }
        return Quota.item(Quota.arr(d as Dictionary, "dip"), i);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var d = Quota.last();
        var sub = PageDraw.subscreen();
        var p = current();
        if (p != 0 && !Quota.hasSession(d)) {
            noSession(dc, sub, p == 1 ? ["CONNECTION", "INTERNET"] : ["DEVICES", "DEVICE"]);
        } else if (p == 1) {
            connection(dc, d as Dictionary, sub);
        } else if (p >= 2) {
            device(dc, d as Dictionary, sub, p - 2);
        } else {
            dataLeft(dc, d, sub);
        }
    }

    // A session page before the phone has sent the session: say so.
    function noSession(dc as Graphics.Dc, sub as Array<Number>?, titles as Array<String>) as Void {
        title(dc, titles, sub);
        if (sub != null) {
            var s = sub as Array<Number>;
            PageDraw.subBackground(dc, s);
            dc.drawText(s[0], s[1], Graphics.FONT_MEDIUM, "?", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        }
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        var y = dc.getHeight() / 2 - fh;
        var w = dc.getWidth();
        var lines = ["not sent yet:", "check the phone app,", "then Send now"];
        for (var i = 0; i < lines.size(); i++) {
            var text = PageDraw.clip(dc, font, lines[i], PageDraw.chord(dc, y, fh));
            dc.drawText(w / 2, y, font, text, Graphics.TEXT_JUSTIFY_CENTER);
            y += fh;
        }
    }

    // The page's title: beside the sub-window where there is one, centred
    // near the top where there is not.
    // [titles] is the title, longest first: beside the sub-window, the first
    // that fits is drawn; without one, the longest, centred.
    // It ends well short of the sub-window: the lens's rim covers pixels
    // outside the circle getSubscreen() reports, and a title drawn up to
    // that circle lost its last letter under it on the Solar 45 mm.
    function title(dc as Graphics.Dc, titles as Array<String>, sub as Array<Number>?) as Void {
        var font = Graphics.FONT_XTINY;
        var fh = dc.getFontHeight(font);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        if (sub != null) {
            var s = sub as Array<Number>;
            var y = s[1] - fh / 2;
            var right = s[0] - s[2] - s[2] / 2 - 4;
            var left = (dc.getWidth() - PageDraw.chord(dc, y, fh)) / 2;
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

    // One small line at the bottom: why the reading is doubtful, or what
    // START does -- the first of [ladder] that fits, at the bottom or,
    // failing that, just under the content where the round screen is wider;
    // each spelling tried in both places before the next, and nothing if
    // none fits. Never above [below], the bottom of what the page drew.
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
        var rungs = ladder as Array<String>;
        var ys = (below + 3 < y) ? [y, below + 3] : [y];
        for (var i = 0; i < rungs.size(); i++) {
            var tw = dc.getTextWidthInPixels(rungs[i], font);
            for (var j = 0; j < ys.size(); j++) {
                if (tw <= PageDraw.chord(dc, ys[j], fh)) {
                    dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                    dc.drawText(dc.getWidth() / 2, ys[j], font, rungs[i], Graphics.TEXT_JUSTIFY_CENTER);
                    return;
                }
            }
        }
    }

    function dataLeft(dc as Graphics.Dc, d as Dictionary?, sub as Array<Number>?) as Void {
        var w = dc.getWidth();
        var share = Quota.left(d);
        title(dc, ["DATA LEFT", "DATA"], sub);

        // The sub-window: the share left, as the bar bent into a ring.
        if (sub != null) {
            var s = sub as Array<Number>;
            PageDraw.subBackground(dc, s);
            PageDraw.ring(dc, s[0], s[1], s[2] - 5, share);
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
        if (!PageDraw.numeric(number) || dc.getTextWidthInPixels(number, big) + dc.getTextWidthInPixels(" " + unit, small) > w * 3 / 4) {
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

        // The bar.
        var barH = (w >= 300) ? 10 : 6;
        var bx = w / 7;
        Draw.bar(dc, bx, y, w - 2 * bx, barH, share);
        y += barH + 3;

        // When the grant lands, and where there is no sub-window, the share
        // after it.
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

            // How much of what is left is paid, as the phone spelled it
            // (whole MiB). Absent from a phone app older than the key.
            var paid = Quota.str(d as Dictionary, "paid");
            if (paid.length() > 0) {
                var pf = Graphics.FONT_XTINY;
                var ph = dc.getFontHeight(pf);
                var text = Draw.fit(dc, pf, [paid + " paid"], PageDraw.chord(dc, y, ph));
                if (text != null) {
                    dc.drawText(w / 2, y, pf, text as String, Graphics.TEXT_JUSTIFY_CENTER);
                    y += ph;
                }
            }
        }

        var mark = (d == null) ? null : Quota.mark(d as Dictionary);
        if (mark != null) {
            footer(dc, mark, y);
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
            PageDraw.subBackground(dc, s);
            if (act.length() > 0) {
                PageDraw.powerIcon(dc, s[0], s[1] + 2, s[2] * 2 / 5);
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
        if (act.length() > 0) {
            var label = Quota.str(d, "actl");
            footer(dc, ["START: " + label, label], y);
        }
    }

    // Device [i]'s page: its name as large as it fits, how it is on the
    // session, and START to take it off where the phone offers that.
    function device(dc as Graphics.Dc, d as Dictionary, sub as Array<Number>?, i as Number) as Void {
        var w = dc.getWidth();
        var count = Pages.devices(d);
        var total = Quota.num(d, "dx");
        if (total < count) {
            total = count;
        }
        var which = (count == 0) ? "0" : (i + 1).toString() + "/" + total.toString();

        // The sub-window: which of how many. Without one, the title says it.
        if (sub != null) {
            title(dc, count == 0 ? ["DEVICES", "DEVICE"] : ["DEVICE"], sub);
            var s = sub as Array<Number>;
            PageDraw.subBackground(dc, s);
            var sf = Graphics.FONT_SMALL;
            if (dc.getTextWidthInPixels(which, sf) > 2 * s[2] - 10) {
                sf = Graphics.FONT_XTINY;
            }
            dc.drawText(s[0], s[1], sf, which, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        } else {
            title(dc, count == 0 ? ["DEVICES"] : ["DEVICE " + which], sub);
        }

        var small = Graphics.FONT_XTINY;
        var sh = dc.getFontHeight(small);
        var y = top(dc, sub);
        if (count == 0) {
            dc.drawText(w / 2, y, small, Quota.str(d, "dat").equals("on") ? "none listed" : "data is off", Graphics.TEXT_JUSTIFY_CENTER);
            y += sh;
        } else {
            var name = Quota.item(Quota.arr(d, "dn"), i);
            y = deviceName(dc, name.length() > 0 ? name : "?", y + 2);
            var role = Quota.item(Quota.arr(d, "dr"), i);
            if (role.length() > 0) {
                dc.drawText(w / 2, y, small, PageDraw.clip(dc, small, role, PageDraw.chord(dc, y, sh)), Graphics.TEXT_JUSTIFY_CENTER);
                y += sh;
            }
            // The phone sends at most Pages.MAX_DEVICES: the last page says how
            // many more there are.
            if (i == count - 1 && total > count) {
                dc.drawText(w / 2, y, small, "+" + (total - count).toString() + " more", Graphics.TEXT_JUSTIFY_CENTER);
                y += sh;
            }
        }

        if (deviceIp(d, i).length() > 0) {
            footer(dc, ["START: disconnect", "disconnect"], y);
        }
    }

    // [name], centred from [y], in the largest font it fits on one line;
    // else split in two where it breaks best; cut to the round edge only
    // when even that does not fit. Returns the y below it.
    function deviceName(dc as Graphics.Dc, name as String, y as Number) as Number {
        var w = dc.getWidth();
        var fonts = [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY] as Array<Graphics.FontType>;
        for (var f = 0; f < fonts.size(); f++) {
            var lh = dc.getFontHeight(fonts[f]);
            if (dc.getTextWidthInPixels(name, fonts[f]) <= PageDraw.chord(dc, y, lh)) {
                dc.drawText(w / 2, y, fonts[f], name, Graphics.TEXT_JUSTIFY_CENTER);
                return y + lh;
            }
        }
        var font = Graphics.FONT_TINY;
        var fh = dc.getFontHeight(font);
        var cut = PageDraw.breakAt(name);
        var lines = [name.substring(0, cut) as String, name.substring(cut, name.length()) as String];
        if (dc.getTextWidthInPixels(lines[0], font) > PageDraw.chord(dc, y, fh)
                || dc.getTextWidthInPixels(lines[1], font) > PageDraw.chord(dc, y + fh, fh)) {
            font = Graphics.FONT_XTINY;
            fh = dc.getFontHeight(font);
        }
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(w / 2, y, font, PageDraw.clip(dc, font, lines[i], PageDraw.chord(dc, y, fh)), Graphics.TEXT_JUSTIFY_CENTER);
            y += fh;
        }
        return y;
    }
}
