import Toybox.Lang;
import Toybox.WatchUi;

// The buttons. UP and DOWN turn the pages; START does the page's one thing,
// and only what the phone's last message offered: on a device's page, take
// that device off. Anything that takes a device off data asks first, in the
// watch's own confirmation.
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
        // A device's page: that device, if the phone offered to take it off.
        var i = view.page - 2;
        var ip = view.deviceIp(dd, i);
        if (ip.length() == 0) {
            return false;
        }
        var name = Quota.item(Quota.arr(dd, "dn"), i);
        WatchUi.pushView(new WatchUi.Confirmation("Disconnect " + (name.length() > 0 ? name : ip) + "?"),
            new SendConfirm({"rm" => ip}, "disconnecting"), WatchUi.SLIDE_IMMEDIATE);
        return true;
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
