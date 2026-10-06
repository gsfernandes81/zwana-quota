import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// The pages behind the glance, turned as the watch's own page loops turn
// theirs: UP and DOWN (or a swipe) sliding the next page in from the side it
// lies on, round from the last page to the first. Three of them:
//
//   0 Data left    QuotaView
//   1 Connection   QuotaView
//   2 Devices      DeviceList, the watch's own list, when the phone has sent
//                  devices; else a QuotaView saying why there are none
//
// The list takes UP and DOWN for itself, as the watch's own lists do, and
// hands them back at either end (DeviceListDelegate.onWrap), so it turns
// with the rest. What START does on a page is the sub-window's to show
// (QuotaView.glyph, and the list's icons); on a screen without one, an
// indicator at the edge says where the page is.
// Not a WatchUi.ViewLoop: on an Instinct the loop has the watch draw its own
// battery over the sub-window (Garmin's bug report "ViewLoop is completely
// broken on Instinct 2", acknowledged and not fixed; the simulator does not
// show it, and neither a layer over the page nor drawing it again after
// the turn covers it).
module Pages {
    // The most devices listed: what the phone sends at most
    // (WatchSession.MAX_DEVICES), and a bound whatever arrives.
    const MAX_DEVICES = 8;
    const COUNT = 3;
    const DEVICES = 2;

    // How many devices the phone listed.
    function devices(d as Dictionary) as Number {
        var n = Quota.arr(d, "dn").size();
        return n < MAX_DEVICES ? n : MAX_DEVICES;
    }

    // Whether the Devices page is the list: only with devices to list.
    function listed(d as Dictionary?) as Boolean {
        return Quota.hasSession(d) && devices(d as Dictionary) > 0;
    }

    // The IP START may ask the phone to take device [i] off with, or "" when
    // there is none: the phone sends one only for a device it will take off,
    // and only when it lets the watch switch (`ctl`).
    function deviceIp(d as Dictionary?, i as Number) as String {
        if (!Quota.canControl(d) || i < 0 || i >= devices(d as Dictionary)) {
            return "";
        }
        return Quota.item(Quota.arr(d as Dictionary, "dip"), i);
    }

    // On a screen without a sub-window, how long the edge indicator stays,
    // in milliseconds from the press: the slide and about a second after,
    // so it does not lie over the page's text for good.
    const INDICATOR_MS = 1300;
    // How often the page is drawn again with nothing new from the phone: what
    // it shows ages on its own -- the countdown to the reset, and a reading
    // becoming "2h ago" or "new day" when the phone has gone quiet.
    const REDRAW_MS = 60000;
    // How long after a confirmation over the list is answered a stale list
    // is made again: once the confirmation has been taken off the top.
    const UNCOVER_MS = 200;

    var at as Number = 0;                // the page last turned to
    var top as WatchUi.View? = null;     // its view
    var list as DeviceList? = null;      // that view, when it is the list
    // A confirmation pushed over the list, not yet answered: only the top
    // of the views can be switched, so the list is not made again under it.
    var covered as Boolean = false;
    // The list no longer has as many items as the last message has devices,
    // and is to be made again as soon as it is on top.
    var stale as Boolean = false;
    var indicating as Boolean = false;   // the edge indicator is up
    // One timer for both: it takes the indicator down, then redraws every
    // REDRAW_MS. A watch app may have only three, and Ask holds another.
    var timer as Timer.Timer? = null;

    // Page [page] and its delegate, with the edge indicator up from now
    // where there is no sub-window. The list opens on its first device, or
    // on its last when [fromBelow]: arrived at going up, from Data.
    function view(page as Number, fromBelow as Boolean) as [WatchUi.Views, WatchUi.InputDelegates] {
        return make(page, fromBelow ? -1 : 0);
    }

    // Page [page], the list opening on item [focus] (-1 for its last).
    function make(page as Number, focus as Number) as [WatchUi.Views, WatchUi.InputDelegates] {
        at = page;
        list = null;
        covered = false;
        stale = false;
        if (page == DEVICES && listed(Quota.last())) {
            var l = new DeviceList(focus);
            list = l;
            top = l;
            return [l, new DeviceListDelegate(l)];
        }
        if (PageDraw.subscreen() == null) {
            indicating = true;
            every(:hide, INDICATOR_MS, false);
        } else if (timer == null) {
            every(:redraw, REDRAW_MS, true);
        }
        var v = new QuotaView(page);
        top = v;
        return [v, new QuotaDelegate(v)];
    }

    function hide() as Void {
        indicating = false;
        every(:redraw, REDRAW_MS, true);
        WatchUi.requestUpdate();
    }

    function redraw() as Void {
        WatchUi.requestUpdate();
    }

    // The timer, from now: [method] after [ms], and again every [ms] if
    // [repeat].
    function every(method as Symbol, ms as Number, repeat as Boolean) as Void {
        if (timer == null) {
            timer = new Timer.Timer();
        }
        var t = timer as Timer.Timer;
        t.stop();
        t.start(new Lang.Method(Pages, method), ms, repeat);
    }

    // Turn by [step] -- 1 for the next page, -1 for the one before, round
    // at either end -- from the page last turned to: not from the view the
    // press reached, which during a slide may still be the one leaving.
    function turn(step as Number) as Void {
        var pair = view((at + step + COUNT) % COUNT, step < 0);
        WatchUi.switchToView(pair[0], pair[1], step > 0 ? WatchUi.SLIDE_UP : WatchUi.SLIDE_DOWN);
    }

    // A new message: the list follows it. The Devices page becomes the list
    // when devices first arrive. The list, once up, is brought into line in
    // place while the number of devices holds (DeviceList.sync); when it
    // changes the page is made again -- the list, on the item that took the
    // pressed one's place, or the page saying why there are none -- since a
    // Menu2 shrunk or grown while shown crashes on the watch. Not under a
    // confirmation: that waits for its answer (uncovered).
    function heard() as Void {
        var d = Quota.last();
        if (list != null) {
            if ((list as DeviceList).sync(d)) {
                stale = false;
                return;
            }
            stale = true;
            if (!covered) {
                rebuild();
            }
        } else if (at == DEVICES && listed(d)) {
            var pair = view(DEVICES, false);
            WatchUi.switchToView(pair[0], pair[1], WatchUi.SLIDE_IMMEDIATE);
        }
    }

    // The Devices page made again, in place of a stale list on top.
    function rebuild() as Void {
        if (list == null || !stale) {
            return;
        }
        var pair = make(DEVICES, (list as DeviceList).picked);
        WatchUi.switchToView(pair[0], pair[1], WatchUi.SLIDE_IMMEDIATE);
    }

    // The confirmation over the list has its answer. It is still on top
    // until its delegate returns, so a stale list is made again a moment
    // later, on the timer, which then goes back to its redraws.
    function uncovered() as Void {
        if (!covered) {
            return;
        }
        covered = false;
        if (stale) {
            every(:again, UNCOVER_MS, false);
        }
    }

    function again() as Void {
        every(:redraw, REDRAW_MS, true);
        if (!covered) {
            rebuild();
        }
    }
}
