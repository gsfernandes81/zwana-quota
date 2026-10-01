import Toybox.Application;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;

// What the glance and the pages draw, from the last message the phone
// sent. The phone does the sums (android/core, WatchPayload): the figures
// arrive as the strings its own widget draws, so this side holds no unit
// ladder, no thresholds and no grades -- nothing to drift from the Python.
//
// What it does decide is whether the reading can still be believed, because
// only the watch knows what time it is *now*:
//
//   new day  the reset has passed since the reading: the figure is
//   (old)    yesterday's, and the grant it does not count has landed
//   2h ago   the reading is older than two send intervals: nothing fresh
//   (old)    has come, whether the phone or the portal is out of reach
//   no time  the reading carries no time, so its age cannot be told
//   (!)
//   offline  the portal said the session was down when it was read
//   (!)
//
// each with the short form under it that a squeezed face draws instead:
// "old" where the figure is out of date, "!" where it cannot be vouched
// for. Never "new" (it would read as fresh), a bare age beside a size, "?"
// (which is "no reading") or "off" (data switched off).
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

    // Keep a message from the phone. False if it was not one, if storage
    // refused it outside the app (in the app it is still held in memory), or
    // if it is older than the one already kept: the background service stores a
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
        // The app holds it in memory first: a refused write must not leave
        // the pages drawing the older message, nor an ask waiting on a `sent`
        // it has already been given. Held only there, it is the app's alone:
        // the glance and the app's next start still read the older copy.
        if (remember) {
            memo = d;
        }
        // Storage can refuse -- a value type it will not keep, its quota, a
        // background process that may not write. Refused is never a crash:
        // the app would exit on it, and the message is not sent again. It is
        // still kept where it is held in memory, in the app; elsewhere there
        // is nowhere else to keep it.
        try {
            Application.Storage.setValue(KEY, d as Application.PropertyValueType);
        } catch (e instanceof Lang.Exception) {
            return remember;
        }
        return true;
    }

    // The foreground app keeps the message in memory once read, since a page
    // is drawn from it several times over; store() updates it. Off in the
    // glance and the background, which are separate processes that must see
    // what the other wrote.
    var memo as Dictionary? = null;
    var remember as Boolean = false;

    function last() as Dictionary? {
        if (remember && memo != null) {
            return memo;
        }
        var d = null;
        try {
            d = Application.Storage.getValue(KEY);
        } catch (e instanceof Lang.Exception) {
            return null;
        }
        var kept = (d instanceof Dictionary) ? d as Dictionary : null;
        if (remember) {
            memo = kept;
        }
        return kept;
    }

    function num(d as Dictionary, key as String) as Number {
        var v = d.get(key);
        return (v instanceof Number) ? v as Number : 0;
    }

    function arr(d as Dictionary, key as String) as Array {
        var v = d.get(key);
        return (v instanceof Array) ? v as Array : [];
    }

    // The string at [i], or "" for anything else there or nothing at all.
    function item(a as Array, i as Number) as String {
        if (i < 0 || i >= a.size()) {
            return "";
        }
        var v = a[i];
        return (v instanceof String) ? v as String : "";
    }

    // Whether the phone sent the data session (`dat`); until then its pages
    // read `not sent yet`.
    function hasSession(d as Dictionary?) as Boolean {
        return d != null && str(d, "dat").length() > 0;
    }

    // Whether the phone lets the watch switch data and take devices off.
    // Only a true Boolean counts, as for `ask`.
    function canControl(d as Dictionary?) as Boolean {
        return d != null && d.get("ctl") == true && hasSession(d);
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

    // Why the figure cannot be taken at face value, or null if it can:
    // longest spelling first, the last short enough for any squeeze, so the
    // warning is drawn rather than cut (the forms are in the header).
    function mark(d as Dictionary) as Array<String>? {
        var now = Time.now().value();
        var reset = num(d, "reset");
        if (reset > 0 && now >= reset) {
            return ["new day", "old"];
        }
        var every = num(d, "every");
        if (every <= 0 || every > DAY) {
            every = 1800;
        }
        var ts = num(d, "ts");
        if (ts <= 0) {
            return ["no time", "!"];
        }
        var age = now - ts;
        if (age > 2 * every) {
            return [ago(age), "old"];
        }
        if (d.get("online") == false) {
            return ["offline", "!"];
        }
        return null;
    }

    // How much of today's pool is left, 0 to 1, or -1 for no reading: the
    // bar's length on the glance and the Data page, and the page's ring;
    // full at the reset and emptying as data is used. Drawing,
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

    // When the grant lands, longest spelling first, for the right end of the
    // glance's title row: the last is the bare clock, the one thing there
    // never dropped. Null for no reading, which says so in words.
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

}
