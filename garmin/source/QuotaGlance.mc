import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The glance, laid out as Garmin's own Body Battery glance is:
//
//   Data Left                 2h ago     title; why not to believe it, if so,
//                                        else how much is paid, if it fits
//   ==========----------                what is left of today, thick, then
//                                       what has gone, thin: full at the
//                                       reset, thinning as data is used
//   301 MiB             (reset)@00:00   what is left; when the grant lands
//
// Every row is the text font: the number fonts may hold digits and little
// else. The reset icon is drawn, not a bitmap, so the glance carries no
// resources. The right-hand side shortens until it fits beside the left,
// and every spelling of it is the reset time, which must survive.
(:glance)
class QuotaGlance extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var d = Quota.last();
        var width = dc.getWidth();
        var font = Graphics.FONT_GLANCE;
        var line = dc.getFontHeight(font);
        // The bar's height: 6px on the Solar's glance, 4 on a short one.
        var bh = (dc.getHeight() >= 56) ? 6 : 4;
        var gap = 3;
        var top = (dc.getHeight() - 2 * line - bh - 2 * gap) / 2;
        if (top < 0) {
            top = 0;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        // Title, in capitals as the watch's own glances have theirs, and the
        // mark on the same row when there is one. The mark is the one that
        // matters, so the title gives way to it. With no mark, the paid part
        // of the figure takes the row's right, but only where it fits beside
        // at least "DATA": it is the one that gives way then.
        var mark = (d == null) ? null : Quota.mark(d);
        var title = "DATA LEFT";
        if (mark != null) {
            dc.drawText(width, top, font, mark as String, Graphics.TEXT_JUSTIFY_RIGHT);
            title = fit(dc, font, ["DATA LEFT", "DATA", ""], width - dc.getTextWidthInPixels(mark as String, font) - 6);
        } else if (d != null && Quota.str(d, "paid").length() > 0) {
            var paid = Quota.str(d, "paid") + " paid";
            var titles = ["DATA LEFT", "DATA"];
            for (var i = 0; i < titles.size(); i++) {
                if (dc.getTextWidthInPixels(titles[i], font) + 6 + dc.getTextWidthInPixels(paid, font) <= width) {
                    title = titles[i];
                    dc.drawText(width, top, font, paid, Graphics.TEXT_JUSTIFY_RIGHT);
                    break;
                }
            }
        }
        dc.drawText(0, top, font, title, Graphics.TEXT_JUSTIFY_LEFT);

        // The bar, as Body Battery draws its own (Draw.bar).
        var y = top + line + gap;
        Draw.bar(dc, 0, y, width, bh, Quota.left(d));

        // What is left, and when the grant lands.
        y += bh + gap;
        var figure = Quota.figure(d);
        dc.drawText(0, y, font, figure, Graphics.TEXT_JUSTIFY_LEFT);
        var room = width - dc.getTextWidthInPixels(figure, font) - 6;
        var ladder = Quota.resetAt(d);
        if (ladder == null) {
            dc.drawText(width, y, font, fit(dc, font, ["open on phone", "no data"], room), Graphics.TEXT_JUSTIFY_RIGHT);
        } else {
            var ir = line * 3 / 10;
            if (ir < 3) {
                ir = 3;
            }
            // The arrowhead reaches past the ring by about half its radius.
            var text = fit(dc, font, ladder as Array<String>, room - 2 * ir - ir / 2 - 4);
            var x = width - dc.getTextWidthInPixels(text, font);
            dc.drawText(width, y, font, text, Graphics.TEXT_JUSTIFY_RIGHT);
            Draw.resetIcon(dc, x - 3 - ir - ir / 2, y + line / 2, ir);
        }
    }

    // The longest spelling that fits; the last, the bare clock, if none does.
    function fit(dc as Graphics.Dc, font as Graphics.FontType, ladder as Array<String>, width as Number) as String {
        for (var i = 0; i < ladder.size(); i++) {
            if (dc.getTextWidthInPixels(ladder[i], font) <= width) {
                return ladder[i];
            }
        }
        return ladder[ladder.size() - 1];
    }
}
