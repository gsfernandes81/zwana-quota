"""What the widget reads from the portal, and today's pool.

``gather`` is driven through ``zwana_quota.fetch`` — the seam the module
already goes through — with documents built from the field names the code
itself reads. No socket is opened.
"""

from __future__ import annotations

import pytest
from conftest import GRANT, active_doc, balance_doc, history_entry
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

import quota_widget as qw
import zwana_quota as zq

ACTIVE = "UserProvider/GetActive"
BALANCE = "Balance/GetForCurrentUser"
HISTORY = "Allocation/GetHistoryForCurrentUser"


@pytest.fixture
def portal(monkeypatch):
    """Answer the three calls ``gather`` makes, and count the logins."""

    class Portal:
        def __init__(self):
            self.docs = {
                BALANCE: balance_doc(),
                ACTIVE: active_doc(),
                HISTORY: [],
            }
            self.logins = 0
            self.session = True
            self.asked: list[str] = []

        def fetch(self, path, env, allow_login=True):
            self.asked.append(path)
            answer = self.docs.get(path)
            if isinstance(answer, Exception):
                raise answer
            return answer

    portal = Portal()
    monkeypatch.setattr(zq, "load_session", lambda: portal.session)
    monkeypatch.setattr(zq, "log_in", lambda env: portal.__setattr__(
        "logins", portal.logins + 1))
    monkeypatch.setattr(zq, "fetch", portal.fetch)
    return portal


def test_a_reading_carries_everything_the_document_is_derived_from(portal, clock):
    portal.docs[BALANCE] = balance_doc(2.5, online=True, profile="nightly")
    portal.docs[ACTIVE] = active_doc(remainder=500_000_000, allocated=9_000_000_000)
    data = qw.gather()

    assert set(data) >= {
        "ts", "credits", "per_credit", "remainder", "allocated", "grant",
        "drawn_today", "online", "profile", "pool", "pool_day", "pool_first_ts",
    }
    assert data["credits"] == 2.5
    assert data["remainder"] == 500_000_000
    assert data["allocated"] == 9_000_000_000
    assert data["online"] is True
    assert data["profile"] == "nightly"
    assert data["ts"] == clock.epoch
    # Everything the drawing reads must survive a JSON round trip to the cache.
    assert qw.derive(data, 0.0, True)


def test_a_usable_session_is_not_logged_in_again(portal, clock):
    portal.session = True
    qw.gather()
    assert portal.logins == 0


def test_no_session_logs_in_first(portal, clock):
    portal.session = False
    qw.gather()
    assert portal.logins == 1
    assert portal.asked[0] == BALANCE


@pytest.mark.parametrize(
    "balance", [None, [], "text", {}, {"Balance": None}, {"Balance": "1.5"}],
    ids=["null", "list", "text", "empty", "none", "string"],
)
def test_a_balance_without_a_number_stops_the_reading(portal, clock, balance):
    portal.docs[BALANCE] = balance
    with pytest.raises(zq.PortalError, match="numeric Balance"):
        qw.gather()


@pytest.mark.parametrize(
    "active", [None, [], {}, {"Remainder": None}, {"Remainder": "500"}],
    ids=["null", "list", "empty", "none", "string"],
)
def test_an_active_provider_without_a_remainder_stops_the_reading(
    portal, clock, active
):
    portal.docs[ACTIVE] = active
    with pytest.raises(zq.PortalError, match="numeric Remainder"):
        qw.gather()


# --------------------------------------------------------------------------- #
# The history: the grant, the day's draws, the rate
# --------------------------------------------------------------------------- #


def test_the_free_top_up_is_the_method_the_cron_uses():
    """Everything else in the history is a paid draw."""
    assert qw.FREE_TOPUP_METHOD == 4


def test_the_nightly_grant_is_read_rather_than_assumed(portal, clock):
    portal.docs[HISTORY] = [history_entry(f"{clock.today}T00:02:19", 12345, free=True)]
    assert qw.gather()["grant"] == 12345


def test_todays_paid_draws_are_summed_and_the_grant_is_not_one_of_them(portal, clock):
    portal.docs[HISTORY] = [
        history_entry(f"{clock.today}T00:02:19", GRANT, free=True),
        history_entry(f"{clock.today}T09:00:00", 40 * 1024**2),
        history_entry(f"{clock.today}T14:00:00", 60 * 1024**2),
    ]
    data = qw.gather()
    assert data["grant"] == GRANT
    assert data["drawn_today"] == 100 * 1024**2


def test_yesterdays_draws_are_not_todays(portal, clock):
    yesterday = (clock.utc - qw.dt.timedelta(days=1)).date().isoformat()
    portal.docs[HISTORY] = [
        history_entry(f"{yesterday}T09:00:00", 400 * 1024**2),
        history_entry(f"{clock.today}T09:00:00", 40 * 1024**2),
    ]
    assert qw.gather()["drawn_today"] == 40 * 1024**2


def test_an_entry_without_a_numeric_allocation_is_stepped_over(portal, clock):
    portal.docs[HISTORY] = [
        history_entry(f"{clock.today}T01:00:00", None),
        {"Date": f"{clock.today}T02:00:00"},
        history_entry(f"{clock.today}T03:00:00", 7 * 1024**2),
    ]
    assert qw.gather()["drawn_today"] == 7 * 1024**2


def test_the_rate_follows_the_providers_unit_cost(portal, clock):
    portal.docs[HISTORY] = [
        history_entry(f"{clock.today}T01:00:00", 1024, unit_cost=1 / 1_000_000),
    ]
    assert qw.gather()["per_credit"] == 1_000_000


@pytest.mark.parametrize(
    "kwargs",
    [{"unit_cost": None}, {"unit_cost": 0}, {"unit_cost": -1.0},
     {"byte_type": False}, {"unit_cost": "cheap"}],
    ids=["missing", "zero", "negative", "seconds not bytes", "not a number"],
)
def test_an_unusable_unit_cost_leaves_the_documented_rate(portal, clock, kwargs):
    portal.docs[HISTORY] = [
        history_entry(f"{clock.today}T01:00:00", 1024, **kwargs),
    ]
    assert qw.gather()["per_credit"] == zq.BYTES_PER_CREDIT


def test_the_history_is_walked_in_date_order_whatever_order_it_arrives_in(
    portal, clock
):
    """The last entry by date sets the rate, so the order cannot come from the
    list's own order — the portal does not promise one."""
    early = history_entry(f"{clock.today}T01:00:00", 1024, unit_cost=1 / 111_111)
    late = history_entry(f"{clock.today}T23:00:00", 1024, unit_cost=1 / 222_222)
    portal.docs[HISTORY] = [late, early]
    assert qw.gather()["per_credit"] == 222_222


# --------------------------------------------------------------------------- #
# Losing the history must not read as "no free data"
# --------------------------------------------------------------------------- #


def test_a_lost_history_keeps_todays_grant_rather_than_zeroing_it(portal, clock):
    portal.docs[HISTORY] = zq.PortalError("history is down")
    previous = {"pool_day": clock.today, "grant": GRANT, "drawn_today": 40 * 1024**2}
    data = qw.gather(previous)
    assert data["grant"] == GRANT
    assert data["drawn_today"] == 40 * 1024**2


def test_a_lost_history_does_not_carry_yesterdays_figures_into_today(portal, clock):
    portal.docs[HISTORY] = zq.PortalError("history is down")
    previous = {"pool_day": "2001-01-01", "grant": GRANT, "drawn_today": 999}
    data = qw.gather(previous)
    assert data["grant"] == 0
    assert data["drawn_today"] == 0


def test_a_previous_reading_missing_its_figures_carries_nothing(portal, clock):
    portal.docs[HISTORY] = zq.PortalError("history is down")
    data = qw.gather({"pool_day": clock.today})
    assert data["grant"] == 0
    assert data["drawn_today"] == 0


def test_an_active_provider_without_an_allocated_total_reads_as_nothing(
    portal, clock
):
    portal.docs[ACTIVE] = {"Remainder": 1000}
    assert qw.gather()["allocated"] == 0


def test_a_lost_history_with_nothing_remembered_is_not_an_error(portal, clock):
    portal.docs[HISTORY] = zq.PortalError("history is down")
    assert qw.gather()["grant"] == 0


# --------------------------------------------------------------------------- #
# day_pool: the 100% the bar is drawn against
# --------------------------------------------------------------------------- #


def test_the_pool_holds_the_remainder_even_when_nothing_explains_it(clock):
    pool = qw.day_pool(remainder=5_000, grant=0, drawn_today=0, previous=None)
    assert pool["pool"] == 5_000
    assert pool["pool_day"] == clock.today
    assert pool["pool_first_ts"] == clock.epoch


def test_the_pool_is_a_high_water_mark_within_the_day(clock):
    previous = {"pool_day": clock.today, "pool": 9_000, "pool_first_ts": clock.epoch - 60}
    pool = qw.day_pool(remainder=1_000, grant=1_000, drawn_today=0, previous=previous)
    assert pool["pool"] == 9_000
    assert pool["pool_first_ts"] == clock.epoch - 60


def test_a_pool_that_has_grown_beats_the_remembered_one(clock):
    previous = {"pool_day": clock.today, "pool": 9_000, "pool_first_ts": clock.epoch}
    pool = qw.day_pool(remainder=1_000, grant=9_000, drawn_today=5_000, previous=previous)
    assert pool["pool"] == 14_000


def test_yesterdays_pool_is_discarded_at_the_reset(clock):
    previous = {"pool_day": "2001-01-01", "pool": 10**12, "pool_first_ts": 0.0}
    pool = qw.day_pool(remainder=1_000, grant=2_000, drawn_today=0, previous=previous)
    assert pool["pool"] == 2_000
    assert pool["pool_first_ts"] == clock.epoch


def test_a_previous_reading_missing_its_figures_is_not_a_crash(clock):
    pool = qw.day_pool(1_000, 2_000, 0, {"pool_day": clock.today})
    assert pool["pool"] == 2_000
    assert pool["pool_first_ts"] == clock.epoch


@settings(suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    remainder=st.integers(0, 2**42),
    grant=st.integers(0, 2**32),
    drawn=st.integers(0, 2**40),
    remembered=st.integers(0, 2**42),
    same_day=st.booleans(),
)
def test_the_pool_covers_everything_that_could_be_in_it(
    clock, remainder, grant, drawn, remembered, same_day
):
    previous = {
        "pool_day": clock.today if same_day else "2001-01-01",
        "pool": remembered,
        "pool_first_ts": clock.epoch - 3600,
    }
    pool = qw.day_pool(remainder, grant, drawn, previous)["pool"]
    assert pool >= 0
    assert pool >= remainder
    assert pool >= grant + drawn
    if same_day:
        # Within a day the pool only ever grows: a shrinking 100% would draw a
        # bar that moves backwards while data is being spent.
        assert pool >= remembered


def test_a_day_with_nothing_in_it_has_a_pool_of_nothing(clock):
    """The high-water mark starts at zero rather than at a byte: a pool that
    invented data would be a bar drawn against a number nobody has."""
    assert qw.day_pool(0, 0, 0, {"pool_day": clock.today})["pool"] == 0
    assert qw.day_pool(0, 0, 0, None)["pool"] == 0
