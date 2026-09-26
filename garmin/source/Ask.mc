import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Asking the phone for a fresh reading, from the full view: START (or a
// tap) sends the phone one small message, and the reading comes back the
// way every reading does.
//
// Offered only when the phone's last message said it is listening (`ask`,
// Quota.canAsk): the phone runs a listener only while its "Let the watch
// ask" setting is on, and a watch should not offer what nobody will answer.
// Nothing here runs in the glance or the background: it is the foreground
// app's alone, and does nothing until a button is pressed.
module Ask {
    // How long an ask is waited on before the view says it went unanswered.
    const WAIT = 60;

    var at as Number = 0;       // when it was last asked, watch time; 0 never
    var before as Number = 0;   // the kept reading's `sent` when it was asked
    var failed as Boolean = false;
    var timer as Timer.Timer? = null;

    function answered() as Boolean {
        var d = Quota.last();
        return d != null && Quota.num(d, "sent") != before;
    }

    function waiting() as Boolean {
        return at > 0 && !failed && !answered() && Time.now().value() - at < WAIT;
    }

    // Ask, unless asking is not offered or an ask is already on its way.
    function start() as Void {
        var d = Quota.last();
        if (!Quota.canAsk(d) || waiting()) {
            return;
        }
        at = Time.now().value();
        before = Quota.num(d as Dictionary, "sent");
        failed = false;
        try {
            Communications.transmit({"ask" => "refresh"}, null, new AskListener());
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

    // The full view's last line, or null when asking is not offered.
    function line(d as Dictionary?) as String? {
        if (!Quota.canAsk(d)) {
            return null;
        }
        if (at == 0 || answered()) {
            return "START: refresh";
        }
        if (failed) {
            return "phone not reached";
        }
        if (waiting()) {
            return "asking phone...";
        }
        return "no answer, START again";
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

// START, or a tap on a touch screen: ask. Back and the rest do what they
// always do.
class QuotaDelegate extends WatchUi.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSelect() as Boolean {
        if (!Quota.canAsk(Quota.last())) {
            return false;
        }
        Ask.start();
        return true;
    }
}
