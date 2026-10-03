import Toybox.Lang;
import Toybox.WatchUi;

// UP and DOWN turn the pages (Pages.turn), from the page this view stands
// for now. START does the page's one thing, and only what the phone's
// last message offered: a fresh reading on the first page, the data switch
// on the Connection page, and on a device's page, taking that device off
// (QuotaView.mc lists them). Anything that takes a device off data asks
// first, in the watch's own confirmation.
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
            return onNextPage();
        }
        if (key == WatchUi.KEY_UP) {
            return onPreviousPage();
        }
        return BehaviorDelegate.onKey(evt);
    }

    // DOWN, or a swipe up: the next page.
    function onNextPage() as Boolean {
        Pages.turn(view.current(), 1);
        return true;
    }

    // UP, or a swipe down: the page before.
    function onPreviousPage() as Boolean {
        Pages.turn(view.current(), -1);
        return true;
    }

    function onSelect() as Boolean {
        var d = Quota.last();
        var page = view.current();
        if (page == 0) {
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
        if (page == 1) {
            var act = Quota.str(dd, "act");
            if (act.length() == 0) {
                return false;
            }
            var message = {"do" => act};
            var question = Quota.str(dd, "cq");
            if (!Ask.free(message)) {
                return true;
            }
            if (question.length() > 0) {
                WatchUi.pushView(new WatchUi.Confirmation(question), new SendConfirm(message), WatchUi.SLIDE_IMMEDIATE);
            } else {
                Ask.send(message);
            }
            return true;
        }
        // A device's page: that device, if the phone offered to take it off.
        var i = page - 2;
        var ip = view.deviceIp(dd, i);
        if (ip.length() == 0) {
            return false;
        }
        var off = {"rm" => ip};
        if (!Ask.free(off)) {
            return true;
        }
        var name = Quota.item(Quota.arr(dd, "dn"), i);
        WatchUi.pushView(new WatchUi.Confirmation("Disconnect " + (name.length() > 0 ? name : ip) + "?"),
            new SendConfirm(off), WatchUi.SLIDE_IMMEDIATE);
        return true;
    }
}

// Yes sends [message], if the phone still allows it; no sends nothing.
class SendConfirm extends WatchUi.ConfirmationDelegate {
    var message as Dictionary;

    function initialize(m as Dictionary) {
        ConfirmationDelegate.initialize();
        message = m;
    }

    // `ctl` is checked again here: a message may have come while the
    // question was up, and a Yes refused for it is told so. The phone
    // checks the request itself as well.
    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            if (Quota.canControl(Quota.last())) {
                Ask.send(message);
            } else {
                Ask.toast("not offered now, not sent");
            }
        }
        return true;
    }
}
