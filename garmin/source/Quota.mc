import Toybox.Application;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Time.Gregorian;

// What the glance and the full view draw, from the last message the phone
// sent. The phone does the sums (android/core, WatchPayload): the figures
// arrive as the strings its own widget draws, so this side holds no unit
// ladder, no thresholds and no grades -- nothing to drift from the Python.
//
// What it does decide is whether the reading can still be believed, because
// only the watch knows what time it is *now*:
//
//   new day  the reset has passed since the reading: the figure is
//            yesterday's, and the grant it does not count has landed
//   2h ago   older than two send intervals: sends have stopped arriving
//   offline  the portal said the session was down when it was read
//
// It never trusts the phone's `live`, which was true when it was sent and
// says nothing about now.
//
// And the reset time is on every face, whatever else is squeezed: it is the
// one thing on it that cannot be inferred from the rest.
(:glance, :background)
module Quota {
    const KEY = "q";
    const DAY = 86400;

    // Keep a message from the phone. False if it was not one, or if it is
    // older than the one already kept: the background service stores a
    // message and also hands it to the app, which may start much later, and
    // that late copy must not replace a newer send. Ordered by when the phone
    // sent it rather than by its number, which starts again from 1 whenever
    // the phone app is reinstalled.
    function store(data as Application.PersistableType?) as Boolean {
        if (!(data instanceof Dictionary)) {
            return false;
        }
        var d = data as Dictionary;
        if (d.get("fig") == null || d.get("reset") == null) {
            return false;
        }
        var kept = last();
        if (kept != null && num(d, "sent") < num(kept, "sent")) {
            return false;
        }
        Application.Storage.setValue(KEY, d as Application.PropertyValueType);
        return true;
    }

    function last() as Dictionary? {
        var d = Application.Storage.getValue(KEY);
        return (d instanceof Dictionary) ? d as Dictionary : null;
    }

    function num(d as Dictionary, key as String) as Number {
        var v = d.get(key);
        return (v instanceof Number) ? v as Number : 0;
    }

    function str(d as Dictionary, key as String) as String {
        var v = d.get(key);
        return (v instanceof String) ? v as String : "";
    }

    // HH:MM in the watch's own zone.
    function clock(epoch as Number) as String {
        var info = Gregorian.info(new Time.Moment(epoch), Time.FORMAT_SHORT);
        return Lang.format("$1$:$2$", [(info.hour as Number).format("%02d"), (info.min as Number).format("%02d")]);
    }

    // The next reset still ahead: the one the phone sent, moved on by whole
    // days if the watch has already passed it.
    function nextReset(d as Dictionary) as Number {
        var reset = num(d, "reset");
        var now = Time.now().value();
        while (reset > 0 && reset <= now) {
            reset += DAY;
        }
        return reset;
    }

    function ago(seconds as Number) as String {
        if (seconds < 5400) {
            return (seconds / 60).toString() + "m ago";
        }
        return ((seconds + 1800) / 3600).toString() + "h ago";
    }

    // Why the figure cannot be taken at face value, or null if it can.
    function mark(d as Dictionary) as String? {
        var now = Time.now().value();
        var reset = num(d, "reset");
        if (reset > 0 && now >= reset) {
            return "new day";
        }
        var every = num(d, "every");
        if (every <= 0) {
            every = 1800;
        }
        var age = now - num(d, "ts");
        if (age > 2 * every) {
            return ago(age);
        }
        if (d.get("online") == false) {
            return "offline";
        }
        return null;
    }

    // The glance's second line, longest first. Every one ends with the reset
    // time, so whichever fits still carries it.
    function detail(d as Dictionary?) as Array<String> {
        if (d == null) {
            return ["open on phone", "no data"];
        }
        var at = clock(nextReset(d));
        var m = mark(d);
        if (m != null) {
            var why = m as String;
            return [why + ", resets " + at, why + ", " + at, at];
        }
        var share = str(d, "share");
        return [share + " left, resets " + at, share + ", resets " + at, share + ", " + at, at];
    }

    function figure(d as Dictionary?) as String {
        return (d == null) ? "quota ?" : str(d, "fig");
    }

    // The full view's lines, below the figure.
    function more(d as Dictionary?) as Array<String> {
        if (d == null) {
            return ["no reading yet", "send one from the", "zwana quota app"];
        }
        var lines = [] as Array<String>;
        var m = mark(d);
        if (m != null) {
            lines.add(m as String);
        }
        lines.add(str(d, "share") + " of today left");
        lines.add("resets " + clock(nextReset(d)) + " +" + str(d, "gfig"));
        lines.add("read " + clock(num(d, "ts")) + ", #" + num(d, "n").toString());
        return lines;
    }
}
