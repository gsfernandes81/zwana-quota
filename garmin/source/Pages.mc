import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// The pages behind the glance, turned as the watch's own page loops turn
// theirs: a view each, UP and DOWN (or a swipe) sliding the next page in
// from the side it lies on, round from the last page to the first. What
// START does on a page is the sub-window's to show (QuotaView.glyph); on a
// screen without one, an indicator at the edge says where the page is.
// Not a WatchUi.ViewLoop: on an Instinct the loop has the watch draw its own
// battery over the sub-window (Garmin's bug report "ViewLoop is completely
// broken on Instinct 2", acknowledged and not fixed; the simulator does not
// show it, and neither a layer over the page nor drawing it again after
// the turn covers it).
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

    // On a screen without a sub-window, how long the edge indicator stays,
    // in milliseconds from the press: the slide and about a second after,
    // so it does not lie over the page's text for good.
    const INDICATOR_MS = 1300;

    var top as QuotaView? = null;      // the page last turned to
    var indicating as Boolean = false; // the edge indicator is up
    var timer as Timer.Timer? = null;  // takes it down

    // Page [page] and its delegate (START, and UP and DOWN to turn), with
    // the edge indicator up from now where there is no sub-window.
    function view(page as Number) as [QuotaView, QuotaDelegate] {
        if (PageDraw.subscreen() == null) {
            indicating = true;
            if (timer == null) {
                timer = new Timer.Timer();
            }
            var t = timer as Timer.Timer;
            t.stop();
            t.start(new Lang.Method(Pages, :hide), INDICATOR_MS, false);
        }
        var v = new QuotaView(page);
        top = v;
        return [v, new QuotaDelegate(v)];
    }

    function hide() as Void {
        indicating = false;
        WatchUi.requestUpdate();
    }

    // Turn by [step] -- 1 for the next page, -1 for the one before, round
    // at either end -- from the page last turned to: not from the view the
    // press reached, which during a slide may still be the one leaving.
    function turn(step as Number) as Void {
        var n = count(Quota.last());
        var from = top != null ? (top as QuotaView).page : 0;
        var pair = view((clamp(from, n) + step + n) % n);
        WatchUi.switchToView(pair[0], pair[1], step > 0 ? WatchUi.SLIDE_UP : WatchUi.SLIDE_DOWN);
    }
}
