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
// Garmin has an asynchronous event told: one when it is sent ("asking
// phone", whatever was asked: the phone decides), one if the phone cannot
// be reached, one if no answer comes within WAIT, and one when an ask is
// refused -- another on its way, or no longer offered. An answer needs
// none: the page it changes is redrawn.
//
// Offered only when the phone's last message said it is listening (`ask`,
// and `ctl` for switching): the phone runs its listener only while its
// settings allow, and a watch should not offer what nobody will answer. The
// phone checks every request against the portal again before acting on it.
// Nothing here runs in the glance or the background.
module Ask {
    // How long an ask is waited on before it is called unanswered.
    const WAIT = 60;

    var current as Asking? = null;   // the last ask; null before the first
    var timer as Timer.Timer? = null;

    // Whether the ask is on its way: made, not failed, not answered, and
    // not yet waited on for WAIT.
    function waiting() as Boolean {
        if (current == null) {
            return false;
        }
        var a = current as Asking;
        return !a.failed && !a.answered() && Time.now().value() - a.at < WAIT;
    }

    function refresh() as Void {
        send({"ask" => "refresh"});
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
        var same = message.toString().equals((current as Asking).message);
        toast(same ? "sent, waiting for phone" : "phone busy, not sent");
        return false;
    }

    // Send [message], unless asking is not offered or one is on its way.
    function send(message as Dictionary) as Void {
        var d = Quota.last();
        if (!Quota.canAsk(d) || !free(message)) {
            return;
        }
        var a = new Asking(Quota.num(d as Dictionary, "sent"), message.toString());
        current = a;
        try {
            Communications.transmit(message, null, new AskListener(a));
        } catch (e instanceof Lang.Exception) {
            unreached(a);
            return;
        }
        toast("asking phone");
        // Once the wait is over, say so if nothing came. One timer, kept.
        if (timer == null) {
            timer = new Timer.Timer();
        }
        var t = timer as Timer.Timer;
        t.stop();
        t.start(new Lang.Method(Ask, :expired), (WAIT + 1) * 1000, false);
    }

    // Ask [a] did not reach the phone. A late failure of an earlier ask says
    // nothing about the one now waited on.
    function unreached(a as Asking) as Void {
        if (a != current) {
            return;
        }
        a.failed = true;
        toast("phone not reached");
    }

    function expired() as Void {
        if (current != null) {
            var a = current as Asking;
            if (!a.failed && !a.answered()) {
                toast("no answer from phone");
            }
        }
    }

    function toast(text as String) as Void {
        WatchUi.showToast(text, null);
    }
}

// One ask: when it was made, the reading it was made against, what it was,
// and whether it failed to reach the phone. A new one replaces it on each
// send, so its listener can tell whether it is still the one waited on.
class Asking {
    var at as Number;          // watch time when it was made
    var before as Number;      // the kept reading's `sent` then
    // The message, spelt: every ask is one key, so its toString is a stable
    // spelling of it to tell a repeat by.
    var message as String;
    var failed as Boolean = false;

    function initialize(sent as Number, m as String) {
        at = Time.now().value();
        before = sent;
        message = m;
    }

    // Whether a reading has come since: the answer, as every reading comes.
    function answered() as Boolean {
        var d = Quota.last();
        return d != null && Quota.num(d, "sent") != before;
    }
}

// Hears whether ask [a] reached the phone.
class AskListener extends Communications.ConnectionListener {
    var ask as Asking;

    function initialize(a as Asking) {
        ConnectionListener.initialize();
        ask = a;
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        Ask.unreached(ask);
    }
}
