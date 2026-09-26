import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The glance: the figure, and under it the reset -- with the share, or with
// why the figure is not to be believed, when there is room for either.
(:glance)
class QuotaGlance extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var d = Quota.last();
        var width = dc.getWidth();
        var small = Graphics.FONT_GLANCE;
        var big = (Graphics has :FONT_GLANCE_NUMBER) ? Graphics.FONT_GLANCE_NUMBER : Graphics.FONT_GLANCE;

        var figure = Quota.figure(d);
        if (dc.getTextWidthInPixels(figure, big) > width) {
            big = small;
        }
        var detail = fit(dc, small, Quota.detail(d), width);

        var top = (dc.getHeight() - dc.getFontHeight(big) - dc.getFontHeight(small)) / 2;
        if (top < 0) {
            top = 0;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(0, top, big, figure, Graphics.TEXT_JUSTIFY_LEFT);
        dc.drawText(0, top + dc.getFontHeight(big), small, detail, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // The longest spelling that fits; the last one, the bare clock, if none does.
    function fit(dc as Graphics.Dc, font as Graphics.FontType, ladder as Array<String>, width as Number) as String {
        for (var i = 0; i < ladder.size(); i++) {
            if (dc.getTextWidthInPixels(ladder[i], font) <= width) {
                return ladder[i];
            }
        }
        return ladder[ladder.size() - 1];
    }
}
