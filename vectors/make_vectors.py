#!/usr/bin/env python3
"""Golden vectors: what the Python pipeline makes of fixed inputs, as JSON.

The Android app (``android/``) reads the portal itself rather than asking this
checkout, so it carries a second copy of :func:`quota_widget.gather`'s history
walk, :func:`quota_widget.day_pool` and :func:`quota_widget.derive`. A second
copy is a thing that can disagree with the first, and nobody would see it: the
`dlq` runner's guards read this one, the widget reads that one.

So the *numbers* are pinned across the two languages instead of by eye.
``build()`` runs the real functions over a fixed set of inputs, with the clock
frozen, and ``quota.json`` is what came out. ``tests/test_vectors.py`` asserts
the checked-in file still matches the live functions — so a change to the
derivation fails ``make test`` until the file is regenerated (``make vectors``)
— and the Kotlin suite asserts its own port produces the same file.

Only numbers and flags go in. The wording (``free_left_bias``, ``notes``) and
anything that depends on the machine's zone (``reset.local``) are left out:
those are free to move on either side, and a vector holding them would be a
test that stops a phrase being changed.

    python3 vectors/make_vectors.py          # rewrite vectors/quota.json
"""

from __future__ import annotations

import contextlib
import datetime as dt
import json
import random
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import quota_widget as qw  # noqa: E402
import zwana_quota as zq  # noqa: E402

OUT = Path(__file__).resolve().parent / "quota.json"
SCHEMA = 1

#: The README's nightly grant, to the byte.
GRANT = 763 * 1024**2
GIB = 1024**3

#: 2026-09-02 20:30 UTC: mid-afternoon of a day whose reset is hours away.
EPOCH = int(dt.datetime(2026, 9, 2, 20, 30, tzinfo=dt.UTC).timestamp())
#: The reset that opened that day.
DAY_START = int(dt.datetime(2026, 9, 2, tzinfo=dt.UTC).timestamp())
TODAY = "2026-09-02"
YESTERDAY = "2026-09-01"

#: ``UnitCost`` as the portal states it: 1 / 419,430,400 credits per byte.
UNIT_COST = 2.38418579102e-09


# --------------------------------------------------------------------------- #
# A clock that does not move, without pytest
# --------------------------------------------------------------------------- #


@contextlib.contextmanager
def frozen(epoch: float):
    """Pin ``quota_widget``'s two clocks to *epoch* for the duration.

    The same trick as ``tests/conftest.Clock``, done by hand so the script runs
    outside pytest: the module's ``dt`` and ``time`` are swapped for stand-ins
    rather than the real modules being patched, so nothing else in the process
    sees a stopped clock.
    """
    real = dt.datetime

    class Frozen(real):
        @classmethod
        def now(cls, tz=None):
            stamp = real.fromtimestamp(epoch, dt.UTC)
            return stamp.astimezone(tz) if tz else stamp.replace(tzinfo=None)

    saved = qw.dt, qw.time
    qw.dt = types.SimpleNamespace(datetime=Frozen, timedelta=dt.timedelta, UTC=dt.UTC)
    qw.time = types.SimpleNamespace(time=lambda: float(epoch))
    try:
        yield
    finally:
        qw.dt, qw.time = saved


@contextlib.contextmanager
def portal(balance, active, history):
    """Answer ``zwana_quota``'s calls from three documents, and never log in.

    *history* may be an exception instance, which the history call raises —
    that is how ``gather`` learns the history is unavailable.
    """
    answers = {
        "Balance/GetForCurrentUser": balance,
        "UserProvider/GetActive": active,
        "Allocation/GetHistoryForCurrentUser": history,
    }

    def fetch(path, _env, allow_login=True):
        answer = answers[path]
        if isinstance(answer, Exception):
            raise answer
        return answer

    saved = zq.fetch, zq.load_session
    zq.fetch = fetch
    zq.load_session = lambda: True
    try:
        yield
    finally:
        zq.fetch, zq.load_session = saved


# --------------------------------------------------------------------------- #
# Inputs
# --------------------------------------------------------------------------- #


def entry(date, allocation, *, free=False, unit_cost=UNIT_COST, byte_type=True):
    """One ``Allocation/GetHistoryForCurrentUser`` row, in the portal's names."""
    provider = {"IsByteType": byte_type}
    if unit_cost is not None:
        provider["UnitCost"] = unit_cost
    return {
        "Date": date,
        "Allocation": allocation,
        "CreditHistory": {"TopUpMethod": 4 if free else 1},
        "UserProvider": {"Provider": provider},
    }


def balance(credits=3.0, online=True, profile="nightly"):
    return {"Balance": credits, "Online": online, "IP": "10.0.0.9",
            "CronProfileName": profile}


def active(remainder, allocated=12 * GIB):
    return {"Remainder": remainder, "Allocated": allocated}


def a_day(paid_today=(), *, grant=GRANT):
    """Yesterday's grant and a draw, today's grant, and *paid_today* draws."""
    rows = [
        entry(f"{YESTERDAY}T00:02:19", grant, free=True),
        entry(f"{YESTERDAY}T09:00:00", 419_430_400),
        entry(f"{TODAY}T00:02:19", grant, free=True),
    ]
    rows += [entry(f"{TODAY}T{10 + i:02d}:00:00", n) for i, n in enumerate(paid_today)]
    return rows


def gather_cases() -> list[dict]:
    """Portal documents in, a raw reading out: the history walk and the pool."""
    unavailable = zq.PortalError("Allocation/GetHistoryForCurrentUser: HTTP 500")
    same_day = {
        "pool_day": TODAY, "pool": GRANT + 3 * GIB, "pool_first_ts": DAY_START + 139,
        "grant": GRANT, "drawn_today": 40 * 1024**2,
    }
    return [
        {"name": "grant only, nothing drawn",
         "balance": balance(), "active": active(GRANT), "history": a_day()},
        {"name": "two paid draws today",
         "balance": balance(2.5), "active": active(GRANT + 300 * 1024**2),
         "history": a_day([41_943_040, 419_430_400])},
        {"name": "history out of order is sorted before it is read",
         "balance": balance(), "active": active(GRANT),
         "history": list(reversed(a_day([41_943_040])))},
        {"name": "the grant is read, not assumed",
         "balance": balance(), "active": active(500 * 1024**2),
         "history": a_day(grant=512 * 1024**2)},
        {"name": "a changed unit cost follows into per_credit",
         "balance": balance(), "active": active(GRANT),
         "history": a_day() + [entry(f"{TODAY}T12:00:00", 10, unit_cost=1 / 2**28)]},
        {"name": "a time-metered provider does not set the rate",
         "balance": balance(), "active": active(GRANT),
         "history": a_day() + [entry(f"{TODAY}T12:00:00", 10, unit_cost=0.5,
                                     byte_type=False)]},
        {"name": "rows without a numeric allocation are skipped",
         "balance": balance(), "active": active(GRANT),
         "history": a_day() + [entry(f"{TODAY}T12:00:00", None),
                               entry(f"{TODAY}T13:00:00", "40")]},
        {"name": "fractional allocations and remainders truncate",
         "balance": balance(1.75), "active": active(123_456_789.9, allocated=5.5),
         "history": a_day([41_943_040.7])},
        {"name": "figures past 2^32 bytes",
         "balance": balance(9_999.5), "active": active(6 * 2**40 + 7),
         "history": a_day([5 * 2**40, 2**33 + 1])},
        {"name": "previous reading today keeps the pool's high-water mark",
         "balance": balance(), "active": active(GRANT),
         "history": a_day([40 * 1024**2]), "previous": same_day},
        {"name": "previous reading from yesterday is not carried",
         "balance": balance(), "active": active(GRANT),
         "history": a_day(), "previous": {**same_day, "pool_day": YESTERDAY}},
        {"name": "lost history keeps today's grant instead of zeroing it",
         "balance": balance(), "active": active(GRANT // 2),
         "history": unavailable, "previous": same_day},
        {"name": "lost history with nothing to fall back on",
         "balance": balance(), "active": active(GRANT // 2), "history": unavailable},
        {"name": "history that is not a list",
         "balance": balance(), "active": active(GRANT), "history": {"odd": True}},
        {"name": "offline, no profile name",
         "balance": {"Balance": 0, "Online": None, "CronProfileName": None},
         "active": active(0, allocated=None), "history": []},
        {"name": "no numeric Balance is an error",
         "balance": {"Balance": "3"}, "active": active(GRANT), "history": []},
        {"name": "no numeric Remainder is an error",
         "balance": balance(), "active": {"Allocated": 1}, "history": []},
    ]


def raw(remainder=GRANT, *, grant=GRANT, drawn=0, pool=None, ts=EPOCH,
        first=None, credits=3.0, per_credit=zq.BYTES_PER_CREDIT, online=True):
    """A raw reading of the shape ``gather`` returns."""
    return {
        "ts": ts, "credits": credits, "per_credit": per_credit,
        "remainder": remainder, "allocated": 12 * GIB, "grant": grant,
        "drawn_today": drawn, "online": online, "profile": "nightly",
        "pool_day": TODAY,
        "pool": max(grant + drawn, remainder) if pool is None else pool,
        "pool_first_ts": ts if first is None else first,
    }


def derive_cases() -> list[dict]:
    """Raw readings in, derived figures out: the split and its accuracy."""
    carried = 2 * GIB
    named = [
        ("day that started empty", raw(GRANT, first=DAY_START)),
        ("half the grant gone, paid untouched beneath it",
         raw(GRANT // 2 + carried, pool=GRANT + carried)),
        ("grant gone, the rest is paid", raw(carried, pool=GRANT + carried)),
        ("paid drawn today and partly spent", raw(GRANT, drawn=GIB, pool=GRANT + GIB)),
        ("pool below grant plus drawn", raw(100, drawn=5, pool=10)),
        ("pool of zero is floored at one", raw(0, grant=0, pool=0)),
        ("empty", raw(0)),
        ("remainder above the pool", raw(5 * GIB, pool=GIB)),
        ("first reading at the reset", raw(first=DAY_START)),
        ("first reading 299s after", raw(first=DAY_START + 299)),
        ("first reading 300s after", raw(first=DAY_START + 300)),
        ("first reading 301s after", raw(first=DAY_START + 301)),
        ("first reading an hour after", raw(first=DAY_START + 3600)),
        ("first reading before the reset", raw(first=DAY_START - 500)),
        ("past 2^31 bytes", raw(2**31 + 5, pool=2**31 + 5)),
        ("past 2^32 bytes", raw(2**32 + 5, drawn=2**32, pool=2**33)),
        ("32 TiB", raw(32 * 2**40, drawn=31 * 2**40)),
        ("fractional credits", raw(credits=2.35, per_credit=419_430_401)),
        ("offline", raw(online=False)),
    ]
    cases = [
        {"name": name, "raw": reading, "now": EPOCH, "age": 0.0, "live": True}
        for name, reading in named
    ]
    # The reset boundary itself, and a reading carried across it.
    cases += [
        {"name": "exactly at midnight UTC", "raw": raw(ts=DAY_START, first=DAY_START),
         "now": DAY_START, "age": 0.0, "live": True},
        {"name": "one second before midnight",
         "raw": raw(ts=DAY_START - 1, first=DAY_START - 86_000),
         "now": DAY_START - 1, "age": 0.0, "live": True},
        {"name": "a stale reading", "raw": raw(ts=EPOCH - 5000),
         "now": EPOCH, "age": 5000.0, "live": False},
    ]
    # A seeded spread over the magnitudes the property tests use, so the port
    # is held across the whole input space and not only at the named edges.
    rng = random.Random(20260926)
    for i in range(40):
        grant = rng.randrange(0, 2**32)
        drawn = rng.randrange(0, 2**36)
        remainder = rng.randrange(0, 2**40)
        reading = raw(
            remainder,
            grant=grant,
            drawn=drawn,
            pool=max(grant + drawn, remainder) + rng.choice([0, rng.randrange(0, 2**34)]),
            first=DAY_START + rng.randrange(-1000, 80_000),
            credits=round(rng.uniform(0, 10_000), rng.choice([0, 1, 2, 7])),
            per_credit=rng.randrange(1, 2**32),
            online=rng.random() < 0.8,
        )
        cases.append({"name": f"seeded {i}", "raw": reading, "now": EPOCH,
                      "age": 0.0, "live": True})
    return cases


# --------------------------------------------------------------------------- #
# Outputs
# --------------------------------------------------------------------------- #

#: What a derived document is compared on: numbers and flags, never words.
DERIVED = {
    "free": ("grant_bytes", "left_bytes", "used_bytes", "expires"),
    "paid": ("left_bytes", "drawn_today_bytes", "carry_in_bytes"),
    "today": ("pool_bytes", "remainder_bytes", "used_bytes"),
    "reserve": ("credits", "bytes_per_credit", "bytes"),
    "reset": ("utc", "seconds_until"),
    "accuracy": ("free_left_is_upper_bound", "first_reading_after_reset_seconds",
                 "carry_in_observed"),
    "reading": ("online", "live"),
}


def run_gather(case: dict) -> dict:
    with frozen(case.get("now", EPOCH)), portal(
        case["balance"], case["active"], case["history"]
    ):
        try:
            return qw.gather(case.get("previous"))
        except zq.PortalError:
            return {"error": True}


def run_derive(case: dict) -> dict:
    with frozen(case["now"]):
        doc = qw.derive(case["raw"], case["age"], case["live"])
    return {group: {k: doc[group][k] for k in keys} for group, keys in DERIVED.items()}


def build() -> dict:
    """Every case with what the live Python makes of it."""
    gathers = []
    for case in gather_cases():
        history = case["history"]
        written = {k: v for k, v in case.items() if k != "history"}
        # An exception cannot be written down; ``null`` stands for "the history
        # call failed", which is the only thing gather does with one.
        written["history"] = None if isinstance(history, Exception) else history
        written.setdefault("now", EPOCH)
        written["expect"] = run_gather(case)
        gathers.append(written)
    derives = [{**case, "expect": run_derive(case)} for case in derive_cases()]
    return {
        "schema": SCHEMA,
        "about": "Generated by vectors/make_vectors.py from the live Python; "
        "regenerate with `make vectors`, never by hand.",
        "gather": gathers,
        "derive": derives,
    }


def render(vectors: dict) -> str:
    return json.dumps(vectors, indent=1, sort_keys=True) + "\n"


def main() -> int:
    OUT.write_text(render(build()))
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
