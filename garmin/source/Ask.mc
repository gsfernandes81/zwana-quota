import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Asking the phone for something: a fresh reading, a data switch, a device
// off. One small message each, carrying an id, and the answer comes back the
// way every reading does -- in the phone's next message, which lists the ids
// of the asks it has answered (`re`) and beside each what became of it
// (`rw`: "" for done, else a word to show). A phone app older than ids sends
// no `re`, and then any new message, told apart by its `sent`, is the answer.
//
// How an ask goes is told in the watch's own toasts (WatchUi.showToast), as
// Garmin has an asynchronous event told: one when it is sent ("asking
// phone", whatever was asked: the phone decides), one if the phone cannot
// be reached, one if no answer comes within the wait, one with the phone's
// word if it did not do what was asked ("too late", "phone busy"), and one
// when an ask is refused here -- another on its way, or, at a
// confirmation's Yes, no longer allowed (QuotaDelegate). An answer that did
// what was asked needs none: the page it changes is redrawn.
//
// Offered only when the phone's last message said it is listening (`ask`,
// and `ctl` for switching): the phone runs its listener only while its
// settings allow, and a watch should not offer what nobody will answer. The
// phone checks every request against the portal again before acting on it.
// Nothing here runs in the glance or the background.
module Ask {
    // How long a request for a reading is waited on before it is called
    // unanswered: the phone's read, and its send back.
    const WAIT = 60;
    // How long a switch or a removal is waited on. The phone makes one only
    // within QuotaWorker.WATCH_SWITCH_SECONDS (45 s) of hearing it, so none is
    // made after this has run out unless the request itself took longer than
    // that to arrive; tests/test_watch_contract.py holds the two to each other.
    const WAIT_SWITCH = 90;
    // Where the last id is kept, so an app started again does not reuse one
    // the phone still lists as answered.
    const ID_KEY = "aid";

    var current as Asking? = null;   // the last ask; null before the first
    var lastIssued as Number = 0;    // the last id given, while the app runs
    var timer as Timer.Timer? = null;

    // Whether the ask is on its way: made, not failed, not answered, and
    // not yet waited on for its wait.
    function waiting() as Boolean {
        if (current == null) {
            return false;
        }
        var a = current as Asking;
        return !a.failed && !a.answered() && Time.now().value() - a.at < a.wait;
    }

    function refresh() as Void {
        send({"ask" => "refresh"});
    }

    // Whether [message] can be sent now. While another ask is on its way it
    // cannot, and is told so -- checked before a confirmation is asked, so
    // nobody confirms something another ask would stop. The very same
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
        var wait = message.hasKey("ask") ? WAIT : WAIT_SWITCH;
        var a = new Asking(nextId(), wait, Quota.num(d as Dictionary, "sent"), message.toString());
        current = a;
        // Sent with its id, on a copy: [message] stays as it was spelt.
        var out = {"id" => a.id} as Dictionary;
        var keys = message.keys();
        for (var i = 0; i < keys.size(); i++) {
            out.put(keys[i], message.get(keys[i]));
        }
        try {
            Communications.transmit(out, null, new AskListener(a));
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
        t.start(new Lang.Method(Ask, :expired), (wait + 1) * 1000, false);
    }

    // An id no ask has had: one more than the last, and never behind the
    // clock, so ids still move on if storage lost the last one; the last
    // given is also held here, so they move on while storage refuses. No
    // random numbers: nothing about them needs to be unguessable, only new.
    function nextId() as Number {
        var id = Time.now().value();
        if (lastIssued >= id) {
            id = lastIssued + 1;
        }
        try {
            var last = Application.Storage.getValue(ID_KEY);
            if (last instanceof Number && last >= id) {
                id = last + 1;
            }
            Application.Storage.setValue(ID_KEY, id);
        } catch (e instanceof Lang.Exception) {
            // Unkept, the id held here and the clock still move on.
        }
        lastIssued = id;
        return id;
    }

    // A message came from the phone. If it answers the ask waited on with a
    // word, show the word, once.
    function heard() as Void {
        if (current == null) {
            return;
        }
        var a = current as Asking;
        if (a.failed || a.told || !a.answered()) {
            return;
        }
        a.told = true;
        var word = a.word();
        if (word.length() > 0) {
            toast(word);
        }
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

// One ask: its id, how long it is waited on, when it was made, the reading
// it was made against, what it was, and whether it failed to reach the
// phone. A new one replaces it on each send, so its listener can tell
// whether it is still the one waited on.
class Asking {
    var id as Number;
    var wait as Number;        // seconds: Ask.WAIT or Ask.WAIT_SWITCH
    var at as Number;          // watch time when it was made
    var before as Number;      // the kept reading's `sent` then
    // The message, spelt: every ask is one key, so its toString is a stable
    // spelling of it to tell a repeat by.
    var message as String;
    var failed as Boolean = false;
    var told as Boolean = false;   // its answer's word has been shown
    // Once answered, answered for good, with the word it came with: a
    // message that replaces the answering one (sent in the same second, so
    // not older by `sent`) cannot take the answer back.
    var done as Boolean = false;
    var said as String = "";

    function initialize(i as Number, w as Number, sent as Number, m as String) {
        id = i;
        wait = w;
        at = Time.now().value();
        before = sent;
        message = m;
    }

    // Where the phone's last message lists this ask among those it answered:
    // -1 if it does not, -2 if it lists none at all (a phone app older than
    // ids). Bounded by the list's size, which the phone keeps to 16.
    function place() as Number {
        var d = Quota.last();
        if (d == null) {
            return -1;
        }
        var re = d.get("re");
        if (!(re instanceof Array)) {
            return -2;
        }
        var ids = re as Array;
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i] instanceof Number && ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    // Whether the phone has answered it: by its id, or from an older phone
    // app by any message since.
    function answered() as Boolean {
        if (!done) {
            var d = Quota.last();
            var i = place();
            if (i == -2) {
                done = Quota.num(d as Dictionary, "sent") != before;
            } else if (i >= 0) {
                done = true;
                said = Quota.item(Quota.arr(d as Dictionary, "rw"), i);
            }
        }
        return done;
    }

    // What became of it: "" for done or not yet answered, else the phone's word.
    function word() as String {
        return answered() ? said : "";
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
