import Toybox.Lang;
import Toybox.WatchUi;

// The buttons. UP and DOWN turn the pages; START does the page's one thing,
// and only what the phone's last message offered. Anything that takes a
// device off data asks first, in the watch's own confirmation.
class QuotaDelegate extends WatchUi.BehaviorDelegate {
    var view as QuotaView;

    function initialize(v as QuotaView) {
        BehaviorDelegate.initialize();
        view = v;
    }

    // UP and DOWN are taken here as well as through onNextPage and
    // onPreviousPage: which of the two a watch's firmware delivers for its
    // buttons varies, and a press handled here does not arrive again there.
    function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_DOWN) {
            view.turn(1);
            return true;
        }
        if (key == WatchUi.KEY_UP) {
            view.turn(-1);
            return true;
        }
        return BehaviorDelegate.onKey(evt);
    }

    function onNextPage() as Boolean {
        view.turn(1);
        return true;
    }

    function onPreviousPage() as Boolean {
        view.turn(-1);
        return true;
    }

    function onSelect() as Boolean {
        var d = Quota.last();
        if (view.page == 0) {
            if (!Quota.canAsk(d)) {
                return false;
            }
            Ask.refresh();
            return true;
        }
        if (!Quota.canControl(d)) {
            return false;
        }
        var dd = d as Dictionary;
        if (view.page == 1) {
            var act = Quota.str(dd, "act");
            if (act.length() == 0) {
                return false;
            }
            var message = {"do" => act};
            var label = Quota.str(dd, "actl").toLower();
            var question = Quota.str(dd, "cq");
            if (question.length() > 0) {
                WatchUi.pushView(new WatchUi.Confirmation(question), new SendConfirm(message, label), WatchUi.SLIDE_IMMEDIATE);
            } else {
                Ask.send(message, label);
            }
            return true;
        }
        // Devices: the ones that can be taken off, in the watch's own menu.
        var rm = removable(dd);
        if (rm.size() == 0) {
            return false;
        }
        var menu = new WatchUi.Menu2({:title => "Disconnect"});
        for (var i = 0; i < rm.size(); i++) {
            var pair = rm[i] as Array<String>;
            menu.addItem(new WatchUi.MenuItem(pair[0], pair[1], pair[1], null));
        }
        WatchUi.pushView(menu, new DeviceMenu(), WatchUi.SLIDE_UP);
        return true;
    }

    // [name, ip] for each device the phone said can be taken off.
    static function removable(d as Dictionary) as Array<Array<String> > {
        var names = Quota.arr(d, "dn");
        var ips = Quota.arr(d, "dip");
        var out = [] as Array<Array<String> >;
        var n = names.size() < ips.size() ? names.size() : ips.size();
        for (var i = 0; i < n; i++) {
            var ip = Quota.item(ips, i);
            if (ip.length() > 0) {
                var name = Quota.item(names, i);
                out.add([name.length() > 0 ? name : ip, ip]);
            }
        }
        return out;
    }
}

// A device picked from the menu: close the menu, and ask before sending.
class DeviceMenu extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var ip = item.getId();
        if (!(ip instanceof String)) {
            return;
        }
        var name = item.getLabel();
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        WatchUi.pushView(new WatchUi.Confirmation("Disconnect " + name + "?"),
            new SendConfirm({"rm" => ip}, "disconnecting"), WatchUi.SLIDE_IMMEDIATE);
    }
}

// Yes sends [message]; no sends nothing.
class SendConfirm extends WatchUi.ConfirmationDelegate {
    var message as Dictionary;
    var label as String;

    function initialize(m as Dictionary, l as String) {
        ConfirmationDelegate.initialize();
        message = m;
        label = l;
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            Ask.send(message, label);
        }
        return true;
    }
}
