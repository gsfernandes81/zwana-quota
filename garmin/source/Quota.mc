import Toybox.Application;
import Toybox.Lang;
import Toybox.System;
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
//
// Nothing here may throw on what arrives: a message is only data, read
// through num() and str(), which turn a missing or mistyped key into 0 or ""
// rather than a crash, and every loop is bounded by the data's size, never
// by its values. The worst a bad message can do is not be shown.
(:glance, :background)
module Quota {
    const KEY = "q";
    const DAY = 86400;
    // WatchPayload.VERSION. A message of another version is not kept: the
    // last one this app understands stays up and is drawn ageing, which is
    // the honest failure, rather than new keys being read by old rules.
    const VERSION = 1;

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
        if (num(d, "v") != VERSION || d.get("fig") == null || d.get("reset") == null) {
            return false;
        }
        var kept = last();
        if (kept != null && num(d, "sent") < num(kept, "sent")) {
            return false;
        }
        // Storage can refuse -- a value type it will not keep, its quota, a
        // background process that may not write. Refused is not kept, never
        // a crash: the app would exit on it, and the message is not sent
        // again.
        try {
            Application.Storage.setValue(KEY, d as Application.PropertyValueType);
        } catch (e instanceof Lang.Exception) {
            return false;
        }
        return true;
    }

    function last() as Dictionary? {
        var d = null;
        try {
            d = Application.Storage.getValue(KEY);
        } catch (e instanceof Lang.Exception) {
            return null;
        }
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

    function hour24() as Boolean {
        return System.getDeviceSettings().is24Hour;
    }

    // In the watch's own zone and on its own clock: 14:02, or 2:02 pm.
    function clock(epoch as Number) as String {
        var info = Gregorian.info(new Time.Moment(epoch), Time.FORMAT_SHORT);
        var hour = info.hour as Number;
        var min = (info.min as Number).format("%02d");
        if (hour24()) {
            return hour.format("%02d") + ":" + min;
        }
        var h = hour % 12;
        return (h == 0 ? 12 : h).toString() + ":" + min + (hour < 12 ? " am" : " pm");
    }

    // When the grant lands, as the phone's widget says it: 00:00 hrs on a
    // 24-hour clock, 12:00 am on a 12-hour one.
    function resetClock(epoch as Number) as String {
        return hour24() ? clock(epoch) + " hrs" : clock(epoch);
    }

    // The next reset still ahead: the one the phone sent, moved on by whole
    // days if the watch has already passed it. Arithmetic, not a loop, so a
    // reset far in the past costs no more than one a minute old.
    function nextReset(d as Dictionary) as Number {
        var reset = num(d, "reset");
        var now = Time.now().value();
        if (reset > 0 && reset <= now) {
            reset += ((now - reset) / DAY + 1) * DAY;
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
        if (every <= 0 || every > DAY) {
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

    // How much of today's pool is left, 0 to 1, or -1 for no reading: the
    // glance's bar, full at the reset and emptying as data is used. Drawing,
    // not a rule -- the share the phone spelled, as a length -- and read from
    // the whole-KiB figures, so no string is parsed.
    function left(d as Dictionary?) as Float {
        if (d == null) {
            return -1.0;
        }
        var pool = num(d, "pool");
        if (pool <= 0) {
            return -1.0;
        }
        var share = num(d, "rem").toFloat() / pool.toFloat();
        if (share < 0.0) {
            share = 0.0;
        } else if (share > 1.0) {
            share = 1.0;
        }
        return share;
    }

    // The glance's right-hand side, after its reset icon, longest first:
    // when the grant lands. Null for no reading, which says so in words.
    function resetAt(d as Dictionary?) as Array<String>? {
        if (d == null) {
            return null;
        }
        var at = clock(nextReset(d));
        return ["@" + at, at];
    }

    // Whether the phone said it is listening for the watch to ask. Only a
    // true Boolean counts: a phone that never sent `ask` is not listening.
    function canAsk(d as Dictionary?) as Boolean {
        return d != null && d.get("ask") == true;
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
        // After the reset the share is yesterday's, and says so.
        lines.add(str(d, "share") + (m != null && m.equals("new day") ? " left yesterday" : " of today left"));
        var grant = str(d, "gfig");
        var at = resetClock(nextReset(d));
        lines.add(grant.length() > 0 ? "+" + grant + " at " + at : "resets at " + at);
        lines.add("read " + clock(num(d, "ts")) + ", #" + num(d, "n").toString());
        return lines;
    }
}
