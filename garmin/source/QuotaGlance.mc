import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The glance, laid out as Garmin's own Body Battery glance is:
//
//   Data Left                 2h ago     title; why not to believe it, if so
//   [#########...........]              what is left of today: full at the
//                                       reset, emptying as data is used
//   301 MiB           +763 @ 00:00      what is left; the grant, and when
//
// Every row is the text font: the number fonts may hold digits and little
// else. The right-hand figures shorten until they fit beside the left, and
// every spelling ends with the reset time, which must survive.
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
        var bar = dc.getHeight() / 8;
        if (bar < 4) {
            bar = 4;
        } else if (bar > 8) {
            bar = 8;
        }
        var gap = 3;
        var top = (dc.getHeight() - 2 * line - bar - 2 * gap) / 2;
        if (top < 0) {
            top = 0;
        }

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        // Title, and the mark on the same row when there is one.
        dc.drawText(0, top, font, "Data Left", Graphics.TEXT_JUSTIFY_LEFT);
        var mark = (d == null) ? null : Quota.mark(d);
        if (mark != null) {
            dc.drawText(width, top, font, mark as String, Graphics.TEXT_JUSTIFY_RIGHT);
        }

        // The bar: an outline, filled from the left by the share left, so it
        // empties as the day's data goes. No reading, no fill.
        var y = top + line + gap;
        dc.setPenWidth(1);
        dc.drawRoundedRectangle(0, y, width, bar, bar / 2);
        var share = Quota.left(d);
        if (share > 0.0) {
            var fill = (share * width).toNumber();
            if (fill < bar) {
                fill = bar;
            }
            dc.fillRoundedRectangle(0, y, fill, bar, bar / 2);
        }

        // What is left, and the grant with its time.
        y += bar + gap;
        var figure = Quota.figure(d);
        dc.drawText(0, y, font, figure, Graphics.TEXT_JUSTIFY_LEFT);
        var room = width - dc.getTextWidthInPixels(figure, font) - 6;
        dc.drawText(width, y, font, fit(dc, font, Quota.grantAt(d), room), Graphics.TEXT_JUSTIFY_RIGHT);
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
