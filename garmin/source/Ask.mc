import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Asking the phone for something: a fresh reading, a data switch, a device
// off. One small message each, and the answer comes back the way every
// reading does -- a new message from the phone, told apart from the last by
// its `sent`.
//
// How an ask goes is told in the watch's own toasts (WatchUi.showToast), as
// Garmin has an asynchronous event told: one when it is sent, one if the
// phone cannot be reached, one if no answer comes within WAIT. An answer
// needs none: the page it changes is redrawn.
//
// Offered only when the phone's last message said it is listening (`ask`,
// and `ctl` for switching): the phone runs its listener only while its
// settings allow, and a watch should not offer what nobody will answer. The
// phone checks every request against the portal again before acting on it.
// Nothing here runs in the glance or the background.
module Ask {
    // How long an ask is waited on before it is called unanswered.
    const WAIT = 60;

    var at as Number = 0;        // when it was last asked, watch time; 0 never
    var before as Number = 0;    // the kept reading's `sent` when it was asked
    var failed as Boolean = false;
    var what as String = "";     // what was asked, for the toasts
    var asked as String = "";    // the message itself, to tell a repeat by
    var asks as Number = 0;      // counts every ask: the listeners' stamp
    var timer as Timer.Timer? = null;

    function answered() as Boolean {
        var d = Quota.last();
        return d != null && Quota.num(d, "sent") != before;
    }

    function waiting() as Boolean {
        return at > 0 && !failed && !answered() && Time.now().value() - at < WAIT;
    }

    function refresh() as Void {
        if (Quota.canAsk(Quota.last())) {
            send({"ask" => "refresh"}, "refresh");
        }
    }

    // Whether [message] can be sent now. While another ask is on its way it
    // cannot, and is told so -- checked before a confirmation is asked, so
    // nobody confirms something that will not be sent. The very same
    // message again (the same device, not merely another disconnect) is told
    // it is already on its way.
    function free(message as Dictionary) as Boolean {
        if (!waiting()) {
            return true;
        }
        toast(message.toString().equals(asked) ? "sent, waiting for phone" : "phone busy, not sent");
        return false;
    }

    // Send [message], unless asking is not offered or one is on its way.
    // [label] is what the toasts call it.
    function send(message as Dictionary, label as String) as Void {
        var d = Quota.last();
        if (!Quota.canAsk(d) || !free(message)) {
            return;
        }
        at = Time.now().value();
        before = Quota.num(d as Dictionary, "sent");
        failed = false;
        what = label;
        asked = message.toString();
        asks += 1;
        try {
            Communications.transmit(message, null, new AskListener(asks));
        } catch (e instanceof Lang.Exception) {
            unreached(asks);
            return;
        }
        toast(spelled(what));
        // Once the wait is over, say so if nothing came. One timer, kept.
        if (timer == null) {
            timer = new Timer.Timer();
        }
        var t = timer as Timer.Timer;
        t.stop();
        t.start(new Lang.Method(Ask, :expired), (WAIT + 1) * 1000, false);
    }

    function spelled(label as String) as String {
        return label.equals("refresh") ? "asking phone" : label;
    }

    // Ask number [stamp] did not reach the phone. A late failure of an
    // earlier ask says nothing about the one now waited on.
    function unreached(stamp as Number) as Void {
        if (stamp != asks) {
            return;
        }
        failed = true;
        toast("phone not reached");
    }

    function expired() as Void {
        if (at > 0 && !failed && !answered()) {
            toast("no answer from phone");
        }
    }

    function toast(text as String) as Void {
        if (WatchUi has :showToast) {
            WatchUi.showToast(text, null);
        }
    }
}

// Hears whether ask number [stamp] reached the phone.
class AskListener extends Communications.ConnectionListener {
    var stamp as Number;

    function initialize(s as Number) {
        ConnectionListener.initialize();
        stamp = s;
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        Ask.unreached(stamp);
    }
}
