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
// resources.
//
// Two things must always be drawn: the reset time, the one thing on the face
// that cannot be inferred from the rest, and the mark, when there is one.
// What is paid is the first to give way, then the title. The mark goes
// beside the figure where it fits, in its long or its short spelling, else
// in the title's place; there the reset time sheds its "@" and then its icon
// before the mark falls to its short spelling, and the mark is cut only when
// even that does not fit beside the bare clock.
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

        // What is left, and at its right why not to believe it, if so, else
        // how much is paid -- each only where it fits beside the figure.
        var y = top + line + gap + bh + gap;
        var figure = Quota.figure(d);
        dc.drawText(0, y, font, figure, Graphics.TEXT_JUSTIFY_LEFT);
        var room = width - dc.getTextWidthInPixels(figure, font) - 6;
        var mark = (d == null) ? null : Quota.mark(d);
        var above = mark;   // the mark, while it still needs the title's place
        if (d == null) {
            var none = Draw.fit(dc, font, ["open on phone", "no data"], room);
            if (none != null) {
                dc.drawText(width, y, font, none as String, Graphics.TEXT_JUSTIFY_RIGHT);
            }
        } else if (mark != null) {
            var said = Draw.fit(dc, font, mark as Array<String>, room);
            if (said != null) {
                dc.drawText(width, y, font, said as String, Graphics.TEXT_JUSTIFY_RIGHT);
                above = null;
            }
        } else if (Quota.str(d, "paid").length() > 0) {
            var paid = Quota.str(d, "paid") + " paid";
            if (dc.getTextWidthInPixels(paid, font) <= room) {
                dc.drawText(width, y, font, paid, Graphics.TEXT_JUSTIFY_RIGHT);
            }
        }

        // When the grant lands, at the title row's right: the longest
        // spelling, icon first, that leaves the mark its room if it is here;
        // at the least the bare clock alone.
        var used = 0;
        var ladder = Quota.resetAt(d);
        if (ladder != null) {
            var spellings = ladder as Array<String>;
            var ir = line * 3 / 10;
            if (ir < 3) {
                ir = 3;
            }
            // The arrowhead reaches past the ring by about half its radius.
            var icon = 2 * ir + ir / 2 + 4;
            var need = (above == null) ? 0 : dc.getTextWidthInPixels((above as Array<String>)[0], font) + 6;
            var text = Draw.fit(dc, font, spellings, width - icon - need);
            var drawIcon = (text != null);
            if (text == null) {
                text = spellings[spellings.size() - 1];
            }
            var tw = dc.getTextWidthInPixels(text as String, font);
            dc.drawText(width, top, font, text as String, Graphics.TEXT_JUSTIFY_RIGHT);
            used = tw + 6;
            if (drawIcon) {
                Draw.resetIcon(dc, width - tw - 3 - ir - ir / 2, top + line / 2, ir);
                used += icon;
            }
        }

        // The title, in capitals as the watch's own glances have theirs, in
        // what the reset leaves -- or the mark, if it found no room below:
        // its longest spelling that fits, else its shortest, cut.
        var title = null;
        if (above != null) {
            var spelt = above as Array<String>;
            title = Draw.fit(dc, font, spelt, width - used);
            if (title == null) {
                title = Draw.clip(dc, font, spelt[spelt.size() - 1], width - used);
            }
        } else {
            title = Draw.fit(dc, font, ["DATA LEFT", "DATA"], width - used);
        }
        if (title != null) {
            dc.drawText(0, top, font, title as String, Graphics.TEXT_JUSTIFY_LEFT);
        }

        // The bar, as Body Battery draws its own (Draw.bar).
        Draw.bar(dc, 0, top + line + gap, width, bh, Quota.left(d));
    }
}
