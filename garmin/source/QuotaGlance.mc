import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The glance, laid out as Garmin's own Body Battery glance is:
//
//   Data Left                 2h ago     title; why not to believe it, if so
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

        // Title, and the mark on the same row when there is one. The mark is
        // the one that matters, so the title gives way to it.
        var mark = (d == null) ? null : Quota.mark(d);
        var title = "Data Left";
        if (mark != null) {
            dc.drawText(width, top, font, mark as String, Graphics.TEXT_JUSTIFY_RIGHT);
            title = fit(dc, font, ["Data Left", "Data", ""], width - dc.getTextWidthInPixels(mark as String, font) - 6);
        }
        dc.drawText(0, top, font, title, Graphics.TEXT_JUSTIFY_LEFT);

        // The bar, as Body Battery draws its own: what is left is the thick
        // part, from the left; what has gone is a thin line after it, the
        // same length the thick part has lost. No reading, all thin.
        var y = top + line + gap;
        var thin = bar / 3;
        if (thin < 1) {
            thin = 1;
        }
        var share = Quota.left(d);
        var fill = (share > 0.0) ? (share * width).toNumber() : 0;
        if (fill > 0 && fill < bar) {
            fill = bar;
        }
        if (fill < width) {
            dc.fillRectangle(fill, y + (bar - thin) / 2, width - fill, thin);
        }
        if (fill > 0) {
            dc.fillRoundedRectangle(0, y, fill, bar, bar / 2);
        }

        // What is left, and when the grant lands.
        y += bar + gap;
        var figure = Quota.figure(d);
        dc.drawText(0, y, font, figure, Graphics.TEXT_JUSTIFY_LEFT);
        var room = width - dc.getTextWidthInPixels(figure, font) - 6;
        var ladder = Quota.resetAt(d);
        if (ladder == null) {
            dc.drawText(width, y, font, fit(dc, font, ["open on phone", "no data"], room), Graphics.TEXT_JUSTIFY_RIGHT);
        } else {
            var r = line * 3 / 10;
            if (r < 3) {
                r = 3;
            }
            var icon = 2 * r + 4;
            var text = fit(dc, font, ladder as Array<String>, room - icon);
            var x = width - dc.getTextWidthInPixels(text, font);
            dc.drawText(width, y, font, text, Graphics.TEXT_JUSTIFY_RIGHT);
            resetIcon(dc, x - 2 - r, y + line / 2, r);
        }
    }

    // A circular arrow, clockwise: an open ring with a head at its gap.
    function resetIcon(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        dc.setPenWidth(r > 5 ? 2 : 1);
        // From 45 degrees round to 345, anticlockwise: open at the right.
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, 45, 345);
        dc.setPenWidth(1);
        // The head at the 45-degree end, pointing on round clockwise
        // (down and to the right on screen).
        var px = cx + r * 0.7071;
        var py = cy - r * 0.7071;
        // Unit vectors at 45 degrees: along the ring, and across it.
        var k = 0.7071;
        var a = r * 0.7;
        var b = a * 0.8;
        dc.fillPolygon([
            [(px + a * k).toNumber(), (py + a * k).toNumber()],
            [(px - b * k).toNumber(), (py + b * k).toNumber()],
            [(px + b * k).toNumber(), (py - b * k).toNumber()],
        ]);
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
