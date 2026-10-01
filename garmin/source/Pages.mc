import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// The pages behind the glance, as a WatchUi.ViewLoop: Garmin's own page
// loop, which slides each page in as the watch's own carousels do ("Use
// Transitions to Suggest Page Loops"), turns on UP and DOWN or a swipe, and
// shows the watch's own page indicator after each turn.
//
// How many pages there are is the phone's to say -- one per device on the
// session -- and a new message can change it while the loop is on screen.
// A loop is built for a count and never asked to change it: when a message
// brings a different count, the loop is replaced by one built for the new
// count, on the same page where that page still exists. That holds whether
// or not the firmware reads getSize() again on its own.
//
// The replacing waits while something else is on top of the loop (the
// watch's confirmation, before a device is taken off or data is switched):
// replacing the top view then would replace the confirmation, not the loop.
// Whether the loop is on top is asked of the view stack itself
// (getCurrentView), not inferred from onShow and onHide. It is caught up
// when a page is shown again, or at the next message.
module Pages {
    // The most devices given a page each: what the phone sends at most
    // (WatchSession.MAX_DEVICES), and a bound on the pages whatever arrives.
    const MAX_DEVICES = 8;

    var built as Number = 0;      // the count the loop on screen was built for
    var at as Number = 0;         // the page last shown
    var stale as Boolean = false; // the count changed while the loop was covered
    var timer as Timer.Timer? = null;

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
    // rule for a page that is no longer there, for the loop and the views.
    function clamp(page as Number, n as Number) as Number {
        return page < 0 ? 0 : (page >= n ? n - 1 : page);
    }

    // A loop for the pages there are now, opening on [page], or on the last
    // page if there are no longer that many.
    function loop(page as Number) as [WatchUi.ViewLoop, WatchUi.ViewLoopDelegate] {
        var n = count(Quota.last());
        built = n;
        stale = false;
        var l = new WatchUi.ViewLoop(new QuotaPages(n), {:page => clamp(page, n), :wrap => true});
        return [l, new WatchUi.ViewLoopDelegate(l)];
    }

    // Whether the loop is the top of the view stack -- the loop itself or one
    // of its pages, however the firmware reports it -- and so the view that
    // switchToView would replace.
    function onTop() as Boolean {
        var top = WatchUi.getCurrentView()[0];
        return top instanceof WatchUi.ViewLoop || top instanceof QuotaView;
    }

    // A message arrived: redraw, and replace the loop if its count is no
    // longer the count of pages there are.
    function refit() as Void {
        if (count(Quota.last()) == built) {
            stale = false;
            WatchUi.requestUpdate();
            return;
        }
        if (!onTop()) {
            stale = true;
            return;
        }
        var pair = loop(at);
        WatchUi.switchToView(pair[0], pair[1], WatchUi.SLIDE_IMMEDIATE);
    }

    // Page [page] is on screen. A count that changed while the loop was
    // covered is caught up now -- just after, not inside the view's onShow,
    // which is no place to replace the view being shown. One timer, kept.
    function shown(page as Number) as Void {
        at = page;
        if (stale) {
            if (timer == null) {
                timer = new Timer.Timer();
            }
            var t = timer as Timer.Timer;
            t.stop();
            t.start(new Lang.Method(Pages, :refit), 50, false);
        }
    }
}

// The loop's pages: a view and its START delegate for each, for the count
// the loop was built with.
class QuotaPages extends WatchUi.ViewLoopFactory {
    var size as Number;

    function initialize(n as Number) {
        ViewLoopFactory.initialize();
        size = n;
    }

    function getSize() as Number {
        return size;
    }

    function getView(page as Number) as [WatchUi.ViewLoopFactory.Views] or [WatchUi.ViewLoopFactory.Views, WatchUi.ViewLoopFactory.Delegates] {
        var view = new QuotaView(page);
        return [view, new QuotaDelegate(view)];
    }
}
