import Toybox.Application;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Readings to draw in the simulator, where no phone sends any. Debug builds
// only: (:debug) code is left out of a build made with -r, which is how
// .github/workflows/garmin-prg.yml builds every .prg it publishes, so none
// of this reaches a watch. Holding UP (MENU) on a page loads the next one;
// on the Connection page, 25 seconds later instead -- time to open the device
// list, which the hold does not reach, and see it change under the focus.
//
// Each is a message as WatchPayload and WatchSession spell it, with the
// times made from now, so a reading is as fresh or as old as it says.
(:debug)
module Fixture {
    var at as Number = -1;
    var timer as Timer.Timer? = null;

    function soon() as Void {
        if (timer == null) {
            timer = new Timer.Timer();
        }
        (timer as Timer.Timer).start(new Lang.Method(Fixture, :next), 25000, false);
    }

    function next() as Void {
        var all = scenarios();
        at = (at + 1) % (all.size() + 1);
        if (at == all.size()) {
            // Last, nothing heard from the phone at all.
            Application.Storage.deleteValue(Quota.KEY);
            Quota.memo = null;
        } else {
            var d = all[at];
            d["sent"] = Time.now().value() + at;
            Quota.store(d);
        }
        Pages.heard();
        WatchUi.requestUpdate();
    }

    function base(fig as String, remMib as Number, free as String, paid as String) as Dictionary {
        var now = Time.now().value();
        return {
            "v" => Quota.VERSION, "ts" => now - 60, "sent" => now, "every" => 1800,
            "reset" => now + 3 * 3600 + 12 * 60, "rem" => remMib * 1024, "pool" => 763 * 1024,
            "online" => true, "fig" => fig, "free" => free, "paid" => paid,
            "ask" => true, "re" => [], "rw" => [],
        };
    }

    // The reset 70 s off: the page, left open, turns to "new day" on its own.
    function resetSoon(d as Dictionary) as Dictionary {
        d["reset"] = Time.now().value() + 70;
        return d;
    }

    function session(d as Dictionary, names as Array<String>, roles as Array<String>,
            kinds as Array<String>, ips as Array<String>, total as Number) as Dictionary {
        d["dat"] = "on";
        d["dsub"] = "switched on here";
        d["dn"] = names;
        d["dr"] = roles;
        d["dg"] = kinds;
        d["dx"] = total;
        d["ctl"] = true;
        d["act"] = "TURN_OFF_EVERYWHERE";
        d["actl"] = "Turn off for all";
        d["cq"] = "Data off for all " + total.toString() + " devices?";
        d["dip"] = ips;
        return d;
    }

    function many(n as Number) as Array<Array<String>> {
        var names = ["This phone", "gavins-thinkpad", "desktop", "work-laptop", "tv-box", "pixel-tablet", "kitchen-pc", "nas"];
        var dn = [] as Array<String>;
        var dr = [] as Array<String>;
        var dg = [] as Array<String>;
        var dip = [] as Array<String>;
        for (var i = 0; i < n; i++) {
            dn.add(names[i]);
            dr.add(i == 0 ? "switched data on" : "joined");
            dg.add(i == 0 ? "mainphone" : (i == 4 ? "phone" : ""));
            dip.add(i == 0 ? "" : "10.1.0." + (100 + i).toString());
        }
        return [dn, dr, dg, dip];
    }

    function scenarios() as Array<Dictionary> {
        var three = many(3);
        var four = many(4);
        var six = many(6);
        var eight = many(8);
        var stale = base("540 MiB", 540, "128 MiB", "412 MiB");
        stale["ts"] = Time.now().value() - 2 * 3600;
        var off = base("1.68 GiB", 763, "763 MiB", "960 MiB");
        off["dat"] = "off";
        off["dsub"] = "";
        off["dn"] = [];
        off["dr"] = [];
        off["dg"] = [];
        off["dx"] = 0;
        off["ctl"] = true;
        off["act"] = "TURN_ON";
        off["actl"] = "Turn on";
        off["cq"] = "";
        off["dip"] = [];
        var old = base("12.3 TiB", 700, "", "");
        old.remove("free");
        old.remove("paid");
        old["ask"] = false;
        return [
            session(base("540 MiB", 540, "128 MiB", "412 MiB"), three[0], three[1], three[2], three[3], 3),
            session(base("1023 MiB", 763, "611 MiB", "412 MiB"), four[0], four[1], four[2], four[3], 4),
            session(stale, six[0], six[1], six[2], six[3], 6),
            session(resetSoon(base("3 MiB", 3, "0 MiB", "3 MiB")), eight[0], eight[1], eight[2], eight[3], 11),
            off,
            old,
        ] as Array<Dictionary>;
    }
}
