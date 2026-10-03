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

    var at as Number = 0;                // the page last turned to
    var top as WatchUi.View? = null;     // its view
    var list as DeviceList? = null;      // that view, when it is the list
    var indicating as Boolean = false;   // the edge indicator is up
    var timer as Timer.Timer? = null;    // takes it down

    // Page [page] and its delegate, with the edge indicator up from now
    // where there is no sub-window. The list opens on its first device, or
    // on its last when [fromBelow]: arrived at going up, from Data.
    function view(page as Number, fromBelow as Boolean) as [WatchUi.Views, WatchUi.InputDelegates] {
        at = page;
        list = null;
        if (page == DEVICES && listed(Quota.last())) {
            var l = new DeviceList(fromBelow);
            list = l;
            top = l;
            return [l, new DeviceListDelegate(l)];
        }
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
        var pair = view((at + step + COUNT) % COUNT, step < 0);
        WatchUi.switchToView(pair[0], pair[1], step > 0 ? WatchUi.SLIDE_UP : WatchUi.SLIDE_DOWN);
    }

    // A new message: the list follows it. The Devices page becomes the list
    // when devices first arrive; the list, once up, stays up and is brought
    // into line in place (DeviceList.sync), since a confirmation may lie
    // over it and only the top of the views can be switched.
    function heard() as Void {
        var d = Quota.last();
        if (list != null) {
            (list as DeviceList).sync(d);
        } else if (at == DEVICES && listed(d)) {
            var pair = view(DEVICES, false);
            WatchUi.switchToView(pair[0], pair[1], WatchUi.SLIDE_IMMEDIATE);
        }
    }
}
