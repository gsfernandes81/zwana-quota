import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Behind the glance: the figure large, and the small print the glance has no
// room for -- the share of today, the reset and tonight's grant, when it was
// read, and which message this is, so a new send can be told from the last.
class QuotaView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var d = Quota.last();
        var big = Graphics.FONT_MEDIUM;
        var small = Graphics.FONT_XTINY;
        var lines = Quota.more(d);
        // Asking for a reading, only when the phone said it is listening.
        // On a view that already has a status line the read time goes to
        // make room: the status says the reading's age, and five lines
        // under the Solar's sub-window would run off the round screen.
        var asking = Ask.line(d);
        if (asking != null) {
            if (lines.size() >= 4) {
                lines = lines.slice(0, lines.size() - 1);
            }
            lines.add(asking as String);
        }

        var height = dc.getFontHeight(big) + lines.size() * dc.getFontHeight(small);
        var y = (dc.getHeight() - height) / 2;
        var x = dc.getWidth() / 2;
        // The Instinct's sub-window (the small round inset, top right) is
        // drawn over whatever is under it: start below it, as long as the
        // block still ends on the screen.
        if (WatchUi has :getSubscreen) {
            var sub = WatchUi.getSubscreen();
            if (sub != null) {
                var below = sub.y + sub.height + 2;
                var last = dc.getHeight() - height - 4;
                y = (y < below) ? ((below < last) ? below : last) : y;
            }
        }
        if (y < 0) {
            y = 0;
        }

        dc.drawText(x, y, big, Quota.figure(d), Graphics.TEXT_JUSTIFY_CENTER);
        y += dc.getFontHeight(big);
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(x, y, small, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += dc.getFontHeight(small);
        }
    }
}
