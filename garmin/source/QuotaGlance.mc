import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The glance, laid out as Garmin's own Body Battery glance is:
//
//   DATA LEFT           (reset)@00:00   title; when the grant lands
//   ==========----------                what is left of today, thick, then
//                                       what has gone, thin: full at the
//                                       reset, thinning as data is used
//   301 MiB                   2h ago    what is left; why not to believe it,
//                                       if so, else how much is paid
//
// Every row is the text font: the number fonts may hold digits and little
// else. The reset icon is drawn, not a bitmap, so the glance carries no
// resources. The reset time is drawn first and the title gives way to it,
// down to nothing: every spelling of it is the reset time, which must
// survive. Beside the figure, the mark comes before what is paid, which is
// the one that gives way; a mark with no room there takes the title's place.
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

        // When the grant lands, at the title row's right: the icon, then the
        // longest spelling that fits.
        var used = 0;
        var ladder = Quota.resetAt(d);
        if (ladder != null) {
            var ir = line * 3 / 10;
            if (ir < 3) {
                ir = 3;
            }
            // The arrowhead reaches past the ring by about half its radius.
            var icon = 2 * ir + ir / 2 + 4;
            var text = fit(dc, font, ladder as Array<String>, width - icon);
            var tw = dc.getTextWidthInPixels(text, font);
            dc.drawText(width, top, font, text, Graphics.TEXT_JUSTIFY_RIGHT);
            Draw.resetIcon(dc, width - tw - 3 - ir - ir / 2, top + line / 2, ir);
            used = tw + icon + 6;
        }

        // What is left, and at its right why not to believe it, if so, else
        // how much is paid -- each only where it fits beside the figure.
        var y = top + line + gap + bh + gap;
        var figure = Quota.figure(d);
        dc.drawText(0, y, font, figure, Graphics.TEXT_JUSTIFY_LEFT);
        var room = width - dc.getTextWidthInPixels(figure, font) - 6;
        var mark = (d == null) ? null : Quota.mark(d);
        var below = false;
        if (d == null) {
            dc.drawText(width, y, font, fit(dc, font, ["open on phone", "no data"], room), Graphics.TEXT_JUSTIFY_RIGHT);
        } else if (mark != null) {
            if (dc.getTextWidthInPixels(mark as String, font) <= room) {
                dc.drawText(width, y, font, mark as String, Graphics.TEXT_JUSTIFY_RIGHT);
                below = true;
            }
        } else if (Quota.str(d, "paid").length() > 0) {
            var paid = Quota.str(d, "paid") + " paid";
            if (dc.getTextWidthInPixels(paid, font) <= room) {
                dc.drawText(width, y, font, paid, Graphics.TEXT_JUSTIFY_RIGHT);
            }
        }

        // The title, in capitals as the watch's own glances have theirs, in
        // what the reset leaves -- or the mark, if it found no room below.
        var titles = (mark != null && !below) ? [mark as String, ""] : ["DATA LEFT", "DATA", ""];
        dc.drawText(0, top, font, fit(dc, font, titles, width - used), Graphics.TEXT_JUSTIFY_LEFT);

        // The bar, as Body Battery draws its own (Draw.bar).
        Draw.bar(dc, 0, top + line + gap, width, bh, Quota.left(d));
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
