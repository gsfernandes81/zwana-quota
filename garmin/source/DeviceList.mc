import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// The Devices page: the devices on the session, this phone first, as the
// watch's own list (Menu2), so it scrolls and focuses as the watch's own
// lists do. Each item is the device's name over how it is on (`dn`, `dr`).
//
// On a watch with a sub-window the list draws the focused item's icon there,
// as the other pages draw theirs (DeviceIcon): the device with a cross when
// START can take it off, else which device it is -- the one that switched
// data on, with a star, or this phone. Where there is no sub-window the items
// have no icons.
//
// UP at the top and DOWN at the bottom hand back to the other pages
// (DeviceListDelegate.onWrap); BACK leaves the app, as on every page.
class DeviceList extends WatchUi.Menu2 {
    var icons as Boolean;
    var shown as Number = 0;   // how many items are in the list

    function initialize(fromBelow as Boolean) {
        Menu2.initialize({:title => "DEVICES"});
        icons = PageDraw.subscreen() != null;
        var d = Quota.last();
        sync(d);
        if (fromBelow && shown > 0) {
            setFocus(shown - 1);
        }
    }

    // Bring the items into line with [d], in place: each item's id is its
    // place in the phone's list, so a press reads what is there now. A list
    // emptied while it is up keeps one item saying so: a Menu2 cannot be
    // shown empty, and the list is not switched away from under a
    // confirmation (Pages.heard).
    function sync(d as Dictionary?) as Void {
        var n = (d != null && Pages.listed(d)) ? Pages.devices(d as Dictionary) : 0;
        var items = [] as Array<WatchUi.MenuItem>;
        for (var i = 0; i < n; i++) {
            items.add(item(d as Dictionary, i));
        }
        if (n == 0) {
            var why = Quota.hasSession(d) && Quota.str(d as Dictionary, "dat").equals("on") ? "none listed" : "data is off";
            items.add(new WatchUi.MenuItem(why, null, -1, null));
        }
        for (var i = 0; i < items.size(); i++) {
            if (i < shown) {
                updateItem(items[i], i);
            } else {
                addItem(items[i]);
            }
        }
        for (var i = shown - 1; i >= items.size(); i--) {
            deleteItem(i);
        }
        // Not left on an item that is gone: the simulator's list moves the
        // focus back itself, and nothing says a watch's does.
        if (items.size() < shown) {
            setFocus(items.size() - 1);
        }
        shown = items.size();
        WatchUi.requestUpdate();
    }

    // Device [i]: its name, how it is on, and its icon for the sub-window.
    function item(d as Dictionary, i as Number) as WatchUi.MenuItem {
        var name = Quota.item(Quota.arr(d, "dn"), i);
        var role = Quota.item(Quota.arr(d, "dr"), i);
        var label = name.length() > 0 ? name : "?";
        var sub = role.length() > 0 ? role : null;
        if (!icons) {
            return new WatchUi.MenuItem(label, sub, i, null);
        }
        return new WatchUi.IconMenuItem(label, sub, i, new DeviceIcon(d, i), null);
    }
}

// A device's icon in the sub-window, for the focused device: the
// sub-window as every page draws it, white with the glyph in black. The list
// also hands the icon a small cell beside the name (24 x 14 on the Solar);
// nothing is drawn there -- an icon that small reads as a smudge.
class DeviceIcon extends WatchUi.Drawable {
    var id as ResourceId;

    function initialize(d as Dictionary, i as Number) {
        Drawable.initialize({});
        var g = Quota.item(Quota.arr(d, "dg"), i);
        if (Pages.deviceIp(d, i).length() > 0) {
            id = Rez.Drawables.Disconnect;
        } else if (g.equals("main")) {
            id = Rez.Drawables.Main;
        } else if (g.equals("mainphone")) {
            id = Rez.Drawables.MainPhone;
        } else if (g.equals("phone")) {
            id = Rez.Drawables.Phone;
        } else {
            id = Rez.Drawables.Device;
        }
    }

    function draw(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        // The sub-window's canvas, not the cell beside the name.
        if (w < 40 || h < 40) {
            return;
        }
        var s = [w / 2, h / 2, (w < h ? w : h) / 2];
        PageDraw.sub(dc, s);
        PageDraw.glyph(dc, s, id);
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
        var i = item.getId();
        if (!(i instanceof Number)) {
            return;
        }
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
