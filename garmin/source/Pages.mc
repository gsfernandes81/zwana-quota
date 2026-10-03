import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// The pages behind the glance, turned as a WatchUi.ViewLoop turns them: a
// view each, UP and DOWN (or a swipe) sliding the next page in from the
// side it lies on, round from the last page to the first, and the page
// indicator -- an arc of a segment per page on the left edge, beside UP
// and DOWN, this page's segment bold -- drawn for a moment after each turn.
// Not a ViewLoop itself: on an Instinct the loop has the watch draw its own
// battery over the sub-window (Garmin's bug report "ViewLoop is completely
// broken on Instinct 2", acknowledged and not fixed; the simulator does not
// show it, and neither a layer over the page nor drawing it again after
// the turn covers it), and the sub-window is where each page puts its one
// number.
//
// How many pages there are is the phone's to say -- one per device on the
// session -- and a new message can change it at any time. Nothing holds a
// count: a view works out its page against the count there is now
// (QuotaView.current), and a turn goes on from there.
module Pages {
    // The most devices given a page each: what the phone sends at most
    // (WatchSession.MAX_DEVICES), and a bound on the pages whatever arrives.
    const MAX_DEVICES = 8;

    // How many devices have a page of their own.
    function devices(d as Dictionary) as Number {
        var n = Quota.arr(d, "dn").size();
        return n < MAX_DEVICES ? n : MAX_DEVICES;
    }

    // Data, Connection, and a page per device -- one saying so when there are
    // none. At least three, so UP and DOWN always move: a page whose data the
    // phone has not sent yet says so rather than being missing.
    function count(d as Dictionary?) as Number {
        if (!Quota.hasSession(d)) {
            return 3;
        }
        var n = devices(d as Dictionary);
        return 2 + (n > 0 ? n : 1);
    }

    // [page] where there are [n] pages: the last for one past it. The one
    // rule for a page that is no longer there.
    function clamp(page as Number, n as Number) as Number {
        return page < 0 ? 0 : (page >= n ? n - 1 : page);
    }

    // How long the indicator stays after a turn, in milliseconds.
    const INDICATOR_MS = 1500;

    var turned as Number = 0;          // System.getTimer() at the last turn
    var timer as Timer.Timer? = null;  // takes the indicator down again

    // Page [page] and its START delegate, the indicator shown from now:
    // after a turn, and when the pages are first opened.
    function view(page as Number) as [QuotaView, QuotaDelegate] {
        turned = System.getTimer();
        if (timer == null) {
            timer = new Timer.Timer();
        }
        var t = timer as Timer.Timer;
        t.stop();
        t.start(new Lang.Method(Pages, :hide), INDICATOR_MS + 50, false);
        var v = new QuotaView(page);
        return [v, new QuotaDelegate(v)];
    }

    // The indicator's time is up: draw the page without it.
    function hide() as Void {
        WatchUi.requestUpdate();
    }

    // The indicator for page [page] of [n], while it is up: a segment per
    // page round the left edge, top to bottom, this page's bold. Drawn
    // last, over a black edge of its own, so it reads over the page.
    function indicate(dc as Graphics.Dc, page as Number, n as Number) as Void {
        if (System.getTimer() - turned > INDICATOR_MS) {
            return;
        }
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var r = (cx < cy ? cx : cy) - 6;
        var seg = 9;    // degrees per segment
        var gap = 4;    // degrees between them
        var span = n * seg + (n - 1) * gap;
        var start = 180 - span / 2;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(10);
        dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, start - 2, start + span + 2);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        for (var i = 0; i < n; i++) {
            var a = start + i * (seg + gap);
            dc.setPenWidth(i == page ? 6 : 2);
            dc.drawArc(cx, cy, r, Graphics.ARC_COUNTER_CLOCKWISE, a, a + seg);
        }
        dc.setPenWidth(1);
    }

    // Turn from page [from] by [step]: 1 for the next page, -1 for the one
    // before, round at either end.
    function turn(from as Number, step as Number) as Void {
        var n = count(Quota.last());
        var pair = view((from + step + n) % n);
        WatchUi.switchToView(pair[0], pair[1], step > 0 ? WatchUi.SLIDE_UP : WatchUi.SLIDE_DOWN);
    }
}
