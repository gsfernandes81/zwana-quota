"""Fixtures for the zwana-quota suite.

Three things every test in here gets, whether it asks or not.

**The portal is never contacted.** There is no HTTP in these tests at all: the
seams the modules already have are stubbed — :func:`zwana_quota.request` for
the client, :func:`zwana_quota.fetch` for the widget's ``gather`` — and a
socket opened anyway fails the test rather than reaching the vessel's network.
Nothing was added to the modules to make this possible.

**Nothing outside ``tmp_path`` is written.** The cache, the lock, the cookie
jar, the probe log and ``.env`` are all module-level ``Path``s computed from
``Path.home()`` at import, so they are pointed at a temporary home instead.

**No background refresh is ever started.** ``spawn_refresh`` detaches a real
Python process that would go to the portal, so ``subprocess.Popen`` is replaced
by a recorder — which is also how the spawn tests read what it did.

``document()`` is the derived reading the drawing tests are written against —
the ``_fake`` the deleted self-test carried, rebuilt here rather than in the
module: it is built by running a synthetic raw reading through the real
:func:`quota_widget.derive`, so a document that could not come out of the
pipeline cannot be handed to a renderer either.
"""

from __future__ import annotations

import datetime as dt
import http.cookiejar
import io
import os
import re
import socket
import sys
import time
import types
import urllib.error
import urllib.request
from pathlib import Path

import pytest
from hypothesis import HealthCheck, settings

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import quota_widget as qw  # noqa: E402
import zwana_quota as zq  # noqa: E402

settings.register_profile("suite", deadline=None, max_examples=100)
# A mutation run is a few hundred suites rather than one, so inside poodle's
# working copy the property tests take a smaller, derandomised sample: the
# same properties, sampled the same way every time, so a survivor is a
# survivor rather than a sample that happened to miss.
settings.register_profile(
    "mutants",
    deadline=None,
    max_examples=25,
    derandomize=True,
    database=None,
    suppress_health_check=list(HealthCheck),
)
# ``MUT_SOURCE_FILE`` is set by poodle's runner and by nothing else.
settings.load_profile("mutants" if os.environ.get("MUT_SOURCE_FILE") else "suite")

#: The nightly grant the README names: 763 MiB, to the byte.
GRANT = 763 * 1024**2

#: A fixed instant to hang the deterministic tests off: 2026-09-02 20:30 UTC,
#: which is mid-afternoon of a day whose reset is still hours away.
EPOCH = dt.datetime(2026, 9, 2, 20, 30, tzinfo=dt.UTC).timestamp()


# --------------------------------------------------------------------------- #
# Hermetic by default
# --------------------------------------------------------------------------- #


class _Refused(AssertionError):
    """Raised by anything in the suite that tries to reach the network."""


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    """Make any attempt to reach the portal an outright test failure."""

    def refuse(*_args, **_kwargs):
        raise _Refused("the portal must never be contacted from the suite")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)
    monkeypatch.setattr(urllib.request, "urlopen", refuse)
    monkeypatch.setattr(zq, "_opener", types.SimpleNamespace(open=refuse))


@pytest.fixture(autouse=True)
def sandbox(tmp_path, monkeypatch):
    """Point every path the modules write at a temporary home."""
    home = tmp_path / "home"
    cache = home / ".cache" / "zwana"
    cache.mkdir(parents=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setattr(qw, "CACHE", cache / "widget.json")
    monkeypatch.setattr(qw, "LOCK", cache / "refresh.lock")
    monkeypatch.setattr(qw, "PROBE_LOG", cache / "probe.txt")
    monkeypatch.setattr(zq, "CACHE_DIR", cache)
    monkeypatch.setattr(zq, "COOKIE_FILE", cache / "cookies.txt")
    monkeypatch.setattr(zq, "DEFAULT_ENV", home / "zwana-quota" / ".env")
    monkeypatch.setattr(
        zq, "_jar", http.cookiejar.MozillaCookieJar(str(cache / "cookies.txt"))
    )
    return home


@pytest.fixture(autouse=True)
def spawned(monkeypatch):
    """Record detached refreshes instead of starting them."""
    calls: list[tuple] = []

    def fake_popen(argv, **kwargs):
        calls.append((argv, kwargs))
        return types.SimpleNamespace(pid=4242)

    monkeypatch.setattr(qw.subprocess, "Popen", fake_popen)
    return calls


# --------------------------------------------------------------------------- #
# A clock that does not move under the test
# --------------------------------------------------------------------------- #


class Clock:
    """A frozen ``now`` for both the widget's clocks, movable by ``tick``.

    The zone is pinned to UTC with it. Every local spelling on the faces comes
    from ``astimezone()``, so without that the tile's clock reads differently
    on every machine and a test either pins nothing or pins the machine.
    ``test_format`` sweeps the zones deliberately, on its own.
    """

    def __init__(self, monkeypatch, epoch: float = EPOCH) -> None:
        self.epoch = epoch
        monkeypatch.setenv("TZ", "UTC")
        time.tzset()
        real = dt.datetime
        clock = self

        class Frozen(real):  # noqa: D101
            @classmethod
            def now(cls, tz=None):
                stamp = real.fromtimestamp(clock.epoch, dt.UTC)
                return stamp.astimezone(tz) if tz else stamp.astimezone().replace(
                    tzinfo=None
                )

        monkeypatch.setattr(
            qw, "dt", types.SimpleNamespace(datetime=Frozen, timedelta=dt.timedelta,
                                            UTC=dt.UTC)
        )
        monkeypatch.setattr(qw.time, "time", lambda: clock.epoch)

    def tick(self, seconds: float) -> None:
        self.epoch += seconds

    @property
    def utc(self) -> dt.datetime:
        return dt.datetime.fromtimestamp(self.epoch, dt.UTC)

    @property
    def today(self) -> str:
        return self.utc.date().isoformat()


@pytest.fixture
def clock(monkeypatch):
    return Clock(monkeypatch)


# --------------------------------------------------------------------------- #
# Readings and documents
# --------------------------------------------------------------------------- #


def reading(
    remainder: int = GRANT,
    *,
    grant: int = GRANT,
    drawn_today: int = 0,
    pool: int | None = None,
    ts: float = EPOCH,
    day: str | None = None,
    credits: float = 3.0,
    per_credit: int = zq.BYTES_PER_CREDIT,
    online: bool = True,
    allocated: int = 12 * 1024**3,
    pool_first_ts: float | None = None,
    profile: str = "nightly",
) -> dict:
    """A raw reading of the shape :func:`quota_widget.gather` returns."""
    if pool is None:
        pool = max(grant + drawn_today, remainder)
    if day is None:
        day = dt.datetime.fromtimestamp(ts, dt.UTC).date().isoformat()
    return {
        "ts": ts,
        "credits": credits,
        "per_credit": per_credit,
        "remainder": remainder,
        "allocated": allocated,
        "grant": grant,
        "drawn_today": drawn_today,
        "online": online,
        "profile": profile,
        "pool_day": day,
        "pool": pool,
        "pool_first_ts": ts if pool_first_ts is None else pool_first_ts,
    }


def document(
    remainder: int = GRANT,
    *,
    age: float = 0.0,
    live: bool = True,
    **over,
) -> dict:
    """A derived document, straight through the real derivation."""
    return qw.derive(reading(remainder, **over), age, live)


@pytest.fixture
def doc():
    return document


# --------------------------------------------------------------------------- #
# Portal documents, in the field names the code itself reads
# --------------------------------------------------------------------------- #


def balance_doc(credits: float = 3.0, *, online: bool = True, ip: str = "10.0.0.9",
                profile: str = "nightly") -> dict:
    """``Balance/GetForCurrentUser``."""
    return {
        "Balance": credits,
        "Online": online,
        "IP": ip,
        "CronProfileName": profile,
    }


def active_doc(remainder: int = GRANT, allocated: int = 12 * 1024**3) -> dict:
    """``UserProvider/GetActive``."""
    return {"Remainder": remainder, "Allocated": allocated}


def history_entry(
    date: str,
    allocation: int,
    *,
    free: bool = False,
    unit_cost: float | None = 2.38418579102e-09,
    byte_type: bool = True,
) -> dict:
    """One ``Allocation/GetHistoryForCurrentUser`` row."""
    provider: dict = {"IsByteType": byte_type}
    if unit_cost is not None:
        provider["UnitCost"] = unit_cost
    return {
        "Date": date,
        "Allocation": allocation,
        # The literal, not the module's own constant: a fixture that
        # follows the code can never disagree with it.
        "CreditHistory": {"TopUpMethod": 4 if free else 1},
        "UserProvider": {"Provider": provider},
    }


class FakeResponse:
    """What ``_opener.open`` hands back: a context manager over some bytes."""

    def __init__(self, body: bytes) -> None:
        self.body = body

    def read(self) -> bytes:
        return self.body

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_exc) -> bool:
        return False


class FakeOpener:
    """A stand-in for the module's shared opener, recording every request."""

    def __init__(self, answer) -> None:
        self.answer = answer
        self.requests: list[urllib.request.Request] = []

    def open(self, req, timeout=None):
        self.requests.append(req)
        self.timeout = timeout
        outcome = self.answer(req) if callable(self.answer) else self.answer
        if isinstance(outcome, Exception):
            raise outcome
        return FakeResponse(outcome if isinstance(outcome, bytes) else outcome.encode())


def http_error(code: int, body: bytes = b"", reason: str = "Boom"):
    return urllib.error.HTTPError(
        "https://ic.zwana.io/api/x", code, reason, {}, io.BytesIO(body)
    )


# --------------------------------------------------------------------------- #
# Reading a formatted size back
# --------------------------------------------------------------------------- #

_UNITS = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4,
          "K": 1024, "M": 1024**2, "G": 1024**3, "T": 1024**4}
_SIZE = re.compile(r"^(-?[\d,]+(?:\.\d+)?)\s?(B|KiB|MiB|GiB|TiB|K|M|G|T)$")


def parse_size(text: str) -> tuple[float, float]:
    """Read a formatted byte count back as ``(bytes, tolerance)``.

    The tolerance is half the last digit the spelling actually printed, which
    is the most any correct rounding can be out by. Round-tripping through this
    is how the size ladders are checked: it says *the figure is not a lie*
    without pinning which spelling of it the code chose.
    """
    match = _SIZE.match(text.strip())
    assert match, f"not a size: {text!r}"
    digits, unit = match.groups()
    scale = _UNITS[unit]
    places = len(digits.partition(".")[2])
    return float(digits.replace(",", "")) * scale, 0.5 * 10.0**-places * scale + 1e-6


ANSI = re.compile(r"\033\[[\d;]*m")


def uncoloured(text: str) -> str:
    return ANSI.sub("", text)
