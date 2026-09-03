"""The small formatters: sizes, money, ages, the countdown and the reset.

These are checked by round trip and by budget — what the figure says has to be
true to the precision it printed, and it has to fit the room the layouts
promised it — rather than by pinning the spelling, which the layouts are free
to change.
"""

from __future__ import annotations

import datetime as dt

import pytest
from conftest import parse_size, uncoloured
from hypothesis import given
from hypothesis import strategies as st

import quota_widget as qw


# --------------------------------------------------------------------------- #
# size()
# --------------------------------------------------------------------------- #


@given(value=st.integers(0, 2**50))
def test_size_never_lies_about_the_figure(value):
    read, tolerance = parse_size(qw.size(value))
    assert abs(read - value) <= tolerance


@given(value=st.integers(0, 2**50))
def test_size_is_whole_units_below_a_gibibyte_and_two_decimals_above(value):
    text = qw.size(value)
    number, _, unit = text.rpartition(" ")
    assert unit in ("B", "KiB", "MiB", "GiB")
    assert ("." in number) == (unit == "GiB")
    if unit == "GiB":
        assert len(number.partition(".")[2]) == 2
    else:
        # 1024 exactly is reachable by rounding up, at 1023.9995 of a unit.
        assert float(number.replace(",", "")) <= 1024


@pytest.mark.parametrize(
    "value, unit",
    [(0, "B"), (1023, "B"), (1024, "KiB"), (1024**2 - 1, "KiB"),
     (1024**2, "MiB"), (1024**3 - 1, "MiB"), (1024**3, "GiB")],
)
def test_size_changes_unit_at_the_power_of_two(value, unit):
    assert qw.size(value).endswith(unit)


def test_size_separates_thousands_so_a_big_figure_reads(clock):
    assert qw.size(2048 * 1024**3) == "2,048.00 GiB"


# --------------------------------------------------------------------------- #
# money()
# --------------------------------------------------------------------------- #


@given(credits=st.integers(0, 10**6))
def test_whole_dollars_lose_the_pointless_decimals(credits):
    assert qw.money(float(credits)) == qw.money(credits)
    assert "." not in qw.money(credits)
    assert qw.money(credits).startswith("($")
    assert qw.money(credits).endswith(")")


@given(credits=st.floats(0.01, 10**5, allow_nan=False, allow_infinity=False))
def test_part_dollars_keep_the_cents(credits):
    text = qw.money(credits)
    if credits != int(credits):
        assert len(text.partition(".")[2]) == 3  # two digits and the bracket
        assert float(text[2:-1].replace(",", "")) == pytest.approx(credits, abs=0.005)


# --------------------------------------------------------------------------- #
# since()
# --------------------------------------------------------------------------- #


@given(seconds=st.floats(0, 10**7, allow_nan=False))
def test_an_age_is_short_enough_for_the_line_it_shares(seconds):
    text = qw.since(seconds)
    assert len(text) <= 10
    assert text.endswith(" ago")
    assert text[0].isdigit()


@given(seconds=st.floats(0, 10**7, allow_nan=False))
def test_an_age_never_understates_its_unit(seconds):
    text = qw.since(seconds)
    magnitude, unit = float(text.split()[0][:-1]), text.split()[0][-1]
    scale = {"s": 1, "m": 60, "h": 3600}[unit]
    assert abs(magnitude * scale - seconds) <= scale / 2 + 1e-6


@pytest.mark.parametrize(
    "seconds, unit",
    [(0, "s"), (89.4, "s"), (90, "m"), (5399, "m"), (5400, "h"), (86400, "h")],
)
def test_the_age_units_change_where_the_thresholds_are(seconds, unit):
    assert qw.since(seconds).split()[0].endswith(unit)


# --------------------------------------------------------------------------- #
# countdown()
# --------------------------------------------------------------------------- #


@given(seconds=st.integers(-10**6, 86_400))
def test_a_countdown_always_fits_seven_characters_and_never_runs_backwards(seconds):
    """Seven characters over the range it is ever asked for: the reset is
    always within a day, which is what makes the ``--full`` row's arithmetic
    safe. A longer delta would want more room and never arrives."""
    text = qw.countdown(dt.timedelta(seconds=seconds))
    assert len(text) <= 7
    assert not text.startswith("-")
    assert text.endswith("m")


@pytest.mark.parametrize(
    "seconds, expected",
    [(-5, "0m"), (0, "0m"), (59, "0m"), (60, "1m"), (3599, "59m"),
     (3600, "1h 00m"), (31_740, "8h 49m"), (86_399, "23h 59m")],
)
def test_the_countdown_reads_the_way_the_widget_shows_it(seconds, expected):
    assert qw.countdown(dt.timedelta(seconds=seconds)) == expected


# --------------------------------------------------------------------------- #
# next_reset() — the clock the whole thing hangs off
# --------------------------------------------------------------------------- #


@given(
    stamp=st.datetimes(
        min_value=dt.datetime(2020, 1, 1), max_value=dt.datetime(2040, 1, 1)
    )
)
def test_the_next_reset_is_always_ahead_and_never_more_than_a_day_off(stamp):
    now = stamp.replace(tzinfo=dt.UTC)
    reset = qw.next_reset(now)
    assert reset > now
    assert reset - now <= dt.timedelta(days=1)
    assert reset.tzinfo is dt.UTC
    assert (reset.hour, reset.minute, reset.second, reset.microsecond) == (
        qw.RESET_HOUR_UTC, 0, 0, 0
    )


def test_the_top_up_lands_at_midnight_utc():
    """The portal's cron, not the phone's midnight — the vessel changes zone."""
    assert qw.RESET_HOUR_UTC == 0


def test_midnight_utc_itself_means_the_next_one(clock):
    midnight = dt.datetime(2026, 9, 3, 0, 0, tzinfo=dt.UTC)
    assert qw.next_reset(midnight) == midnight + dt.timedelta(days=1)


def test_a_second_before_midnight_means_tonight(clock):
    almost = dt.datetime(2026, 9, 2, 23, 59, 59, tzinfo=dt.UTC)
    assert qw.next_reset(almost) == dt.datetime(2026, 9, 3, tzinfo=dt.UTC)


@pytest.mark.parametrize(
    "zone",
    ["UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kathmandu",
     "Europe/London", "Australia/Sydney"],
)
def test_the_reset_is_one_instant_whatever_side_of_the_date_line_the_vessel_is(zone):
    """The vessel changes zone; the portal's cron does not change its clock."""
    import os
    import time as real_time

    was = os.environ.get("TZ")
    os.environ["TZ"] = zone
    real_time.tzset()
    try:
        now = dt.datetime(2026, 9, 2, 20, 30, tzinfo=dt.UTC)
        reset = qw.next_reset(now)
        assert reset == dt.datetime(2026, 9, 3, tzinfo=dt.UTC)
        # The local spelling is the same instant, whatever day it lands on.
        assert reset.astimezone().timestamp() == reset.timestamp()
    finally:
        if was is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = was
        real_time.tzset()


# --------------------------------------------------------------------------- #
# day_label()
# --------------------------------------------------------------------------- #


def test_a_reset_later_today_is_not_named(clock):
    today = qw.dt.datetime.now().astimezone()
    assert qw.day_label(today) == "today"


def test_a_reset_after_local_midnight_is_named(clock):
    tomorrow = qw.dt.datetime.now().astimezone() + dt.timedelta(days=1)
    assert qw.day_label(tomorrow) == "tmrw"


def test_a_reset_further_out_is_named_by_its_weekday(clock):
    later = qw.dt.datetime.now().astimezone() + dt.timedelta(days=3)
    assert qw.day_label(later) == later.strftime("%a")


@given(days=st.integers(-30, 30))
def test_a_day_label_is_always_short_enough_for_the_row(days):
    when = dt.datetime.now().astimezone() + dt.timedelta(days=days)
    label = qw.day_label(when)
    assert 3 <= len(label) <= 5
    assert label.isascii()


# --------------------------------------------------------------------------- #
# grade() — the three colours and the three levels
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "share, code",
    [(1.0, "1;32"), (0.25, "1;32"), (0.2499, "1;33"), (0.08, "1;33"),
     (0.0799, "1;31"), (0.0, "1;31")],
)
def test_the_colour_changes_where_the_thresholds_are(share, code):
    assert qw.grade(share) == code


@given(share=st.floats(0, 1))
def test_every_grade_has_a_word_for_the_icon(share):
    assert qw.grade(share) in qw.QS_LEVELS


@given(a=st.floats(0, 1), b=st.floats(0, 1))
def test_more_data_left_is_never_a_worse_grade(a, b):
    order = ["1;31", "1;33", "1;32"]
    if a <= b:
        assert order.index(qw.grade(a)) <= order.index(qw.grade(b))


# --------------------------------------------------------------------------- #
# Paint
# --------------------------------------------------------------------------- #


@given(text=st.text(), code=st.sampled_from(["90", "1;32", "31"]))
def test_paint_off_is_the_text_itself(text, code):
    assert qw.Paint(False)(text, code) == text


@given(text=st.text(max_size=40), code=st.sampled_from(["90", "1;32", "31"]))
def test_paint_on_only_wraps_the_text(text, code):
    painted = qw.Paint(True)(text, code)
    assert uncoloured(painted) == text
    assert painted.startswith(f"\033[{code}m")
    assert painted.endswith("\033[0m")


# --------------------------------------------------------------------------- #
# The bands a property test spread over ten million seconds rarely lands in
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize(
    "seconds", [0, 1, 45, 89, 90, 120, 600, 3599, 5399, 5400, 7200, 86_400, 172_800]
)
def test_an_age_reads_back_as_the_age_it_was_given(seconds):
    text = qw.since(seconds)
    magnitude, unit = float(text.split()[0][:-1]), text.split()[0][-1]
    scale = {"s": 1, "m": 60, "h": 3600}[unit]
    assert abs(magnitude * scale - seconds) <= scale / 2


@pytest.mark.parametrize(
    "value",
    [0, 1, 1023, 1024, 1024**2 - 1, 1024**2, 10 * 1024**2, 763 * 1024**2,
     1024**3 - 1, 1024**3, 1_803_886_264, 10 * 1024**3, 1024**4],
)
def test_a_size_reads_back_as_the_size_it_was_given(value):
    read, tolerance = parse_size(qw.size(value))
    assert abs(read - value) <= tolerance
