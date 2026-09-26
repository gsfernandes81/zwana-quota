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

        var height = dc.getFontHeight(big) + lines.size() * dc.getFontHeight(small);
        var y = (dc.getHeight() - height) / 2;
        var x = dc.getWidth() / 2;

        dc.drawText(x, y, big, Quota.figure(d), Graphics.TEXT_JUSTIFY_CENTER);
        y += dc.getFontHeight(big);
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(x, y, small, lines[i], Graphics.TEXT_JUSTIFY_CENTER);
            y += dc.getFontHeight(small);
        }
    }
}
