import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The Devices page: the devices on the session, this phone first, as the
// watch's own list (Menu2), so it scrolls and focuses as the watch's own
// lists do. Each item is the device's name over how it is on (`dn`, `dr`).
//
// On a watch with a sub-window the list draws the focused item's icon there,
// as the other pages draw theirs (SubIcon): the device with a cross when
// START can take it off, else which device it is -- the one that switched
// data on, with a star, or this phone. Plain items with an :icon, which
// shows only in the sub-window: an IconMenuItem also keeps a cell for its
// icon beside the name.
//
// Never grown or shrunk while it is up: on the watch (not in the simulator)
// a Menu2 that has had an item deleted while shown fails its next draw with
// an Array Out Of Bounds in the firmware, no line of this app on the stack,
// and the app is gone -- as it was on taking a device off from here, the
// list one shorter at the phone's answer. Garmin's forum has it on other
// watches ("app crashes when menu items are dynamically updated"). A
// message with as many devices is brought in in place (sync); one with more
// or fewer has the page made again (Pages.heard).
//
// UP at the top and DOWN at the bottom hand back to the other pages
// (DeviceListDelegate.onWrap); BACK leaves the app, as on every page.
class DeviceList extends WatchUi.Menu2 {
    var icons as Boolean;
    var shown as Number = 0;   // how many items are in the list
    var picked as Number = 0;  // the item last pressed, or opened on

    // Made only when there are devices to list (Pages.listed): a Menu2
    // cannot be shown empty. Opens on item [focus], or on the last for -1
    // or past the end.
    function initialize(focus as Number) {
        Menu2.initialize({:title => "DEVICES"});
        icons = PageDraw.subscreen() != null;
        var d = Quota.last();
        shown = (d != null && Pages.listed(d)) ? Pages.devices(d as Dictionary) : 0;
        for (var i = 0; i < shown; i++) {
            addItem(item(d as Dictionary, i));
        }
        picked = (focus < 0 || focus >= shown) ? shown - 1 : focus;
        if (picked > 0) {
            setFocus(picked);
        } else {
            picked = 0;
        }
    }

    // Bring the items into line with [d], in place, if it lists as many
    // devices; false, and nothing changed, if it does not. Each item's id is
    // its place in the phone's list, so a press reads what is there now.
    function sync(d as Dictionary?) as Boolean {
        var n = (d != null && Pages.listed(d)) ? Pages.devices(d as Dictionary) : 0;
        if (n != shown) {
            return false;
        }
        for (var i = 0; i < n; i++) {
            updateItem(item(d as Dictionary, i), i);
        }
        WatchUi.requestUpdate();
        return true;
    }

    // Device [i]: its name, how it is on, and its icon for the sub-window.
    function item(d as Dictionary, i as Number) as WatchUi.MenuItem {
        var name = Quota.item(Quota.arr(d, "dn"), i);
        var role = Quota.item(Quota.arr(d, "dr"), i);
        var label = name.length() > 0 ? name : "?";
        var sub = role.length() > 0 ? role : null;
        return new WatchUi.MenuItem(label, sub, i, icons ? {:icon => new SubIcon(icon(d, i))} : null);
    }

    // What the sub-window shows for device [i]: what START does to it, or
    // else which device it is.
    function icon(d as Dictionary, i as Number) as ResourceId {
        if (Pages.deviceIp(d, i).length() > 0) {
            return Rez.Drawables.Disconnect;
        }
        var g = Quota.item(Quota.arr(d, "dg"), i);
        if (g.equals("main")) {
            return Rez.Drawables.Main;
        } else if (g.equals("mainphone")) {
            return Rez.Drawables.MainPhone;
        } else if (g.equals("phone")) {
            return Rez.Drawables.Phone;
        }
        return Rez.Drawables.Device;
    }
}

// The focused item's sub-window, as every page draws it: white, with the
// glyph [what] in black.
class SubIcon extends WatchUi.Drawable {
    var what as ResourceId;

    function initialize(w as ResourceId) {
        Drawable.initialize({});
        what = w;
    }

    function draw(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var s = [w / 2, h / 2, (w < h ? w : h) / 2];
        PageDraw.sub(dc, s);
        PageDraw.glyph(dc, s, what);
    }
}

// START on a device takes it off, if the phone offered to (`dip`), after
// asking in the watch's own confirmation; UP and DOWN past either end turn
// the page.
class DeviceListDelegate extends WatchUi.Menu2InputDelegate {
    var list as DeviceList;

    function initialize(l as DeviceList) {
        Menu2InputDelegate.initialize();
        list = l;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        // A press that reaches the list while it slides away is not for it.
        if (list != Pages.top) {
            return;
        }
        // A list a message has outgrown, under a confirmation that has gone
        // without telling (Pages.covered): the press is on what it no longer
        // shows, so it only brings the list up to date.
        if (Pages.stale) {
            Pages.covered = false;
            Pages.rebuild();
            return;
        }
        var i = item.getId();
        if (!(i instanceof Number)) {
            return;
        }
        list.picked = i as Number;
        var d = Quota.last();
        var ip = Pages.deviceIp(d, i as Number);
        if (ip.length() == 0) {
            return;
        }
        var off = {"rm" => ip};
        if (!Ask.free(off)) {
            return;
        }
        var name = Quota.item(Quota.arr(d as Dictionary, "dn"), i as Number);
        Pages.covered = true;
        WatchUi.pushView(new WatchUi.Confirmation("Disconnect " + (name.length() > 0 ? name : ip) + "?"),
            new SendConfirm(off), WatchUi.SLIDE_IMMEDIATE);
    }

    // Moving off either end: UP at the top goes back to Connection, DOWN at
    // the bottom on round to Data, rather than the list wrapping round.
    function onWrap(key as WatchUi.Key) as Boolean {
        if (key == WatchUi.KEY_UP) {
            Pages.turn(-1);
        } else if (key == WatchUi.KEY_DOWN) {
            Pages.turn(1);
        }
        return false;
    }
}
