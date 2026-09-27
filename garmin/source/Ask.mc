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
    var what as String = "";     // what was asked, for the status line
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

    // Send [message], unless asking is not offered or one is on its way.
    // [label] is what the status line calls it meanwhile.
    function send(message as Dictionary, label as String) as Void {
        var d = Quota.last();
        if (!Quota.canAsk(d) || waiting()) {
            return;
        }
        at = Time.now().value();
        before = Quota.num(d as Dictionary, "sent");
        failed = false;
        what = label;
        try {
            Communications.transmit(message, null, new AskListener());
        } catch (e instanceof Lang.Exception) {
            failed = true;
        }
        // Redraw once the wait is over, so "asking" does not stand forever.
        if (timer != null) {
            (timer as Timer.Timer).stop();
        }
        timer = new Timer.Timer();
        (timer as Timer.Timer).start(new Lang.Method(Ask, :redraw), (WAIT + 1) * 1000, false);
        WatchUi.requestUpdate();
    }

    function redraw() as Void {
        WatchUi.requestUpdate();
    }

    // How the last ask stands, longest spelling first for the footer to fit,
    // or null when there is nothing to say (never asked, or answered).
    function status() as Array<String>? {
        if (at == 0 || answered()) {
            return null;
        }
        if (failed) {
            return ["phone not reached", "no phone"];
        }
        if (waiting()) {
            return what.equals("refresh") ? ["asking phone...", "asking..."] : [what + "...", "sending..."];
        }
        return ["no answer from phone", "no answer"];
    }
}

class AskListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
        Ask.failed = true;
        WatchUi.requestUpdate();
    }
}
