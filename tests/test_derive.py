"""The derivation: what free and paid mean, and which way each can be wrong.

The load-bearing guarantee is the one the dlq runner's guards are built on —
``free.left_bytes`` is an upper bound and ``paid.left_bytes`` a lower bound —
so it is stated here as a property over every input the derivation accepts,
rather than as a handful of worked figures.
"""

from __future__ import annotations

import datetime as dt

import pytest
from conftest import GRANT, document, reading
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

import quota_widget as qw

readings = st.builds(
    reading,
    remainder=st.integers(0, 2**42),
    grant=st.integers(0, 2**32),
    drawn_today=st.integers(0, 2**40),
    credits=st.floats(0, 10_000, allow_nan=False, allow_infinity=False),
    per_credit=st.integers(1, 2**32),
    online=st.booleans(),
)


@given(raw=readings, age=st.floats(0, 10**6), live=st.booleans())
def test_the_two_halves_of_the_remainder_add_up_to_it(raw, age, live):
    doc = qw.derive(raw, age, live)
    free = doc["free"]["left_bytes"]
    paid = doc["paid"]["left_bytes"]
    assert free + paid == doc["today"]["remainder_bytes"]
    assert free >= 0
    assert paid >= 0
    assert free <= doc["free"]["grant_bytes"]


@given(raw=readings)
def test_nothing_derived_from_a_reading_goes_negative(raw):
    doc = qw.derive(raw, 0.0, True)
    assert doc["today"]["pool_bytes"] >= 1
    assert doc["today"]["used_bytes"] >= 0
    assert doc["free"]["used_bytes"] >= 0
    assert doc["paid"]["carry_in_bytes"] >= 0
    assert doc["paid"]["drawn_today_bytes"] >= 0
    assert doc["accuracy"]["first_reading_after_reset_seconds"] >= 0


@given(
    raw=readings,
    hidden=st.integers(0, 2**42),
)
def test_free_left_is_an_upper_bound_and_paid_left_a_lower_one(raw, hidden):
    """*hidden* is paid data that carried over midnight unobserved.

    The derivation can only ever see a floor on that, so the truth is always
    at or below the free figure it prints and at or above the paid one — which
    is exactly the direction a download guard needs, and the reason the runner
    reads ``free.left_bytes`` and never ``paid.left_bytes`` for a ceiling.
    """
    doc = qw.derive(raw, 0.0, True)
    truth = qw.derive({**raw, "pool": raw["pool"] + hidden}, 0.0, True)
    assert truth["free"]["left_bytes"] <= doc["free"]["left_bytes"]
    assert truth["paid"]["left_bytes"] >= doc["paid"]["left_bytes"]


@given(raw=readings, extra=st.integers(0, 2**40))
def test_more_observed_drawing_never_makes_more_free_data_appear(raw, extra):
    doc = qw.derive(raw, 0.0, True)
    drawn = qw.derive({**raw, "drawn_today": raw["drawn_today"] + extra}, 0.0, True)
    assert drawn["free"]["left_bytes"] <= doc["free"]["left_bytes"]


@given(raw=readings)
def test_carry_in_is_what_the_pool_cannot_explain_any_other_way(raw):
    doc = qw.derive(raw, 0.0, True)
    carry = doc["paid"]["carry_in_bytes"]
    assert carry == max(0, doc["today"]["pool_bytes"] - raw["grant"] - raw["drawn_today"])
    assert doc["accuracy"]["carry_in_observed"] is (carry > 0)


def test_a_day_that_started_empty_states_free_left_exactly():
    """Grant only, nothing drawn, nothing carried: the arithmetic is not a bound."""
    doc = document(remainder=GRANT, grant=GRANT, drawn_today=0)
    assert doc["free"]["left_bytes"] == GRANT
    assert doc["paid"]["left_bytes"] == 0
    assert doc["paid"]["carry_in_bytes"] == 0


def test_the_grant_is_spent_before_the_paid_data():
    """Half the grant gone, paid data untouched underneath it."""
    paid_carried = 2 * 1024**3
    doc = document(
        remainder=GRANT // 2 + paid_carried,
        grant=GRANT,
        drawn_today=0,
        pool=GRANT + paid_carried,
    )
    assert doc["free"]["left_bytes"] == GRANT // 2
    assert doc["paid"]["left_bytes"] == paid_carried
    assert doc["free"]["used_bytes"] == GRANT - GRANT // 2


def test_once_the_grant_is_gone_what_is_left_is_all_paid():
    paid_carried = 2 * 1024**3
    doc = document(remainder=paid_carried, grant=GRANT, pool=GRANT + paid_carried)
    assert doc["free"]["left_bytes"] == 0
    assert doc["paid"]["left_bytes"] == paid_carried
    assert doc["free"]["used_bytes"] == GRANT


# --------------------------------------------------------------------------- #
# The accuracy block: the two fields must never disagree
# --------------------------------------------------------------------------- #


@settings(suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(gap=st.floats(-10_000, 100_000, allow_nan=False))
def test_the_bias_word_and_the_bias_flag_say_the_same_thing(clock, gap):
    last_reset = qw.next_reset(clock.utc) - dt.timedelta(days=1)
    doc = qw.derive(
        reading(pool_first_ts=last_reset.timestamp() + gap, ts=clock.epoch), 0.0, True
    )
    flag = doc["accuracy"]["free_left_is_upper_bound"]
    assert flag is (doc["accuracy"]["free_left_bias"] == "upper_bound")
    assert flag is not (doc["accuracy"]["free_left_bias"] == "exact_if_online")
    # A first reading taken within five minutes of the reset saw the whole day.
    assert flag is (max(0.0, gap) > 300)


def test_a_reading_first_taken_at_the_reset_is_not_flagged_as_a_ceiling(clock):
    last_reset = qw.next_reset(clock.utc) - dt.timedelta(days=1)
    doc = qw.derive(reading(pool_first_ts=last_reset.timestamp()), 0.0, True)
    assert doc["accuracy"]["free_left_is_upper_bound"] is False
    assert doc["accuracy"]["first_reading_after_reset_seconds"] == 0


def test_a_reading_first_taken_hours_into_the_day_is_flagged_as_a_ceiling(clock):
    last_reset = qw.next_reset(clock.utc) - dt.timedelta(days=1)
    doc = qw.derive(
        reading(pool_first_ts=last_reset.timestamp() + 4 * 3600), 0.0, True
    )
    assert doc["accuracy"]["free_left_is_upper_bound"] is True
    assert doc["accuracy"]["first_reading_after_reset_seconds"] == 4 * 3600


def test_the_notes_say_which_way_the_error_runs():
    notes = " ".join(document()["accuracy"]["notes"]).lower()
    assert "upper bound" in notes
    assert "lower bound" in notes


# --------------------------------------------------------------------------- #
# The document other scripts read
# --------------------------------------------------------------------------- #


@given(raw=readings, age=st.floats(0, 10**6), live=st.booleans())
def test_the_reading_block_reports_the_age_and_never_calls_a_cache_live(
    raw, age, live
):
    doc = qw.derive(raw, age, live)
    assert doc["reading"]["live"] is live
    assert doc["reading"]["age_seconds"] == pytest.approx(age, abs=0.05)
    assert doc["reading"]["online"] is bool(raw["online"])
    assert dt.datetime.fromisoformat(doc["reading"]["taken"]).timestamp() == pytest.approx(
        raw["ts"], abs=1
    )


@given(raw=readings)
def test_the_reserve_is_the_credits_at_the_rate_that_was_read(raw):
    doc = qw.derive(raw, 0.0, True)
    assert doc["reserve"]["bytes_per_credit"] == raw["per_credit"]
    assert doc["reserve"]["credits"] == raw["credits"]
    assert doc["reserve"]["bytes"] == int(raw["credits"] * raw["per_credit"])


@settings(suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(raw=readings)
def test_the_reset_is_the_same_instant_in_three_spellings(clock, raw):
    doc = qw.derive(raw, 0.0, True)
    utc = dt.datetime.fromisoformat(doc["reset"]["utc"])
    local = dt.datetime.fromisoformat(doc["reset"]["local"])
    assert utc == local
    assert doc["free"]["expires"] == doc["reset"]["utc"]
    assert 0 < doc["reset"]["seconds_until"] <= 86400
    assert utc.timestamp() - clock.epoch == pytest.approx(
        doc["reset"]["seconds_until"], abs=1
    )


def test_a_credit_is_a_dollar_and_the_document_says_so():
    doc = document(credits=2.5)
    assert doc["reserve"]["currency"] == "USD"
    assert doc["reserve"]["credits"] == 2.5


def test_the_document_states_the_totals_and_when_it_was_made(clock):
    doc = document(allocated=7 * 1024**3)
    assert doc["totals"]["allocated_bytes"] == 7 * 1024**3
    assert dt.datetime.fromisoformat(doc["generated"]) == clock.utc


def test_the_document_declares_its_schema():
    """``--json`` is an interface: something downstream has to be able to tell."""
    assert document()["schema"] == 1


def test_the_document_keys_the_runner_reads_are_all_there():
    doc = document()
    for section, keys in {
        "reading": {"taken", "age_seconds", "live", "online"},
        "free": {"grant_bytes", "left_bytes", "used_bytes", "expires"},
        "paid": {"left_bytes", "drawn_today_bytes", "carry_in_bytes"},
        "today": {"pool_bytes", "remainder_bytes", "used_bytes"},
        "reserve": {"credits", "currency", "bytes_per_credit", "bytes"},
        "reset": {"utc", "local", "seconds_until"},
        "totals": {"allocated_bytes"},
        "accuracy": {"free_left_bias", "free_left_is_upper_bound",
                     "first_reading_after_reset_seconds", "carry_in_observed",
                     "notes"},
    }.items():
        assert set(doc[section]) >= keys, section


# --------------------------------------------------------------------------- #
# The edges the properties are too coarse to land on
# --------------------------------------------------------------------------- #


def test_a_pool_of_nothing_is_still_a_pool_of_one():
    """Everything downstream divides by it."""
    assert qw.derive(reading(0, grant=0, pool=0), 0.0, True)["today"]["pool_bytes"] == 1


def test_a_pool_that_cannot_account_for_itself_carries_nothing_negative():
    """A reading whose pool is a byte under what the day is known to hold —
    the portal lagging its own history — must not report minus one byte."""
    doc = qw.derive(reading(0, grant=1000, drawn_today=0, pool=999), 0.0, True)
    assert doc["paid"]["carry_in_bytes"] == 0
    assert doc["today"]["used_bytes"] >= 0
    assert qw.derive(reading(1000, grant=0, pool=999), 0.0, True)["today"][
        "used_bytes"
    ] == 0


def test_nothing_used_is_nothing_used():
    doc = document(GRANT, grant=GRANT)
    assert doc["free"]["used_bytes"] == 0
    assert doc["today"]["used_bytes"] == 0


def test_the_age_is_kept_to_a_tenth_of_a_second():
    """It is a reading's age, not a stopwatch: the face rounds it to minutes."""
    reported = qw.derive(reading(), 123.456789, False)["reading"]["age_seconds"]
    assert reported == pytest.approx(123.5, abs=1e-9)


def test_a_reading_with_no_first_timestamp_falls_back_to_its_own(clock):
    raw = reading(ts=clock.epoch)
    del raw["pool_first_ts"]
    doc = qw.derive(raw, 0.0, True)
    assert doc["accuracy"]["first_reading_after_reset_seconds"] >= 0


@pytest.mark.parametrize(
    "gap, ceiling", [(0, False), (299, False), (300, False), (301, True), (3600, True)]
)
def test_five_minutes_after_the_reset_is_where_the_ceiling_starts(clock, gap, ceiling):
    """Inside that window the day was watched from the start, so free left is
    the arithmetic and not a bound."""
    last_reset = qw.next_reset(clock.utc) - dt.timedelta(days=1)
    doc = qw.derive(reading(pool_first_ts=last_reset.timestamp() + gap), 0.0, True)
    assert doc["accuracy"]["free_left_is_upper_bound"] is ceiling
