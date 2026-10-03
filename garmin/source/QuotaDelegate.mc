import Toybox.Lang;
import Toybox.WatchUi;

// UP and DOWN turn the pages (Pages.turn). START does the page's one
// thing, and only what the phone's last message offered: a fresh reading on
// the first page, the data switch on the Connection page (QuotaView.mc lists
// them); the device list has its own delegate (DeviceList.mc). Anything that
// takes a device off data asks first, in the watch's own confirmation.
class QuotaDelegate extends WatchUi.BehaviorDelegate {
    var view as QuotaView;

    function initialize(v as QuotaView) {
        BehaviorDelegate.initialize();
        view = v;
    }

    // DOWN, or a swipe up: the next page.
    function onNextPage() as Boolean {
        Pages.turn(1);
        return true;
    }

    // UP, or a swipe down: the page before.
    function onPreviousPage() as Boolean {
        Pages.turn(-1);
        return true;
    }

    function onSelect() as Boolean {
        // A press that reaches a page while it slides away is not for it.
        if (view != Pages.top) {
            return true;
        }
        var d = Quota.last();
        var page = view.page;
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
        var act = Quota.str(dd, "act");
        if (page != 1 || act.length() == 0) {
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

    (:debug)
    function onMenu() as Boolean {
        if (view.page == 1) {
            Fixture.soon();
        } else {
            Fixture.next();
        }
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
