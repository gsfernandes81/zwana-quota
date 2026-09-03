"""``--full``: the detailed box, and ``--line``: the status bar.

The alignment note in the module is the thing being defended here — nothing
but ASCII and the one measured middot inside the frame, no per-row right
border to drift, and blank rows that stay genuinely blank.
"""

from __future__ import annotations

import re

import pytest
from conftest import GRANT, document, uncoloured
from hypothesis import given
from hypothesis import strategies as st

import quota_widget as qw

PLAIN = qw.Paint(False)
BOX_GLYPHS = set("╭╮╰╯│─")
ANSI_CODES = re.compile(r"\033\[([\d;]*)m")

#: What the box is drawn for: a phone widget and a terminal, at the
#: magnitudes a day's allowance and a modest reserve actually reach. The
#: headline states the figure twice — what is left, and the pool it is out of
#: — so a hundred-gibibyte reading would want more columns than the box has;
#: the widget is calibrated for a 763 MiB day.
values = st.integers(0, 10 * 1024**3)

#: The box "wants about 40 columns"; :data:`quota_widget.INNER` is its default
#: and its narrowest sensible setting.
widths = st.integers(qw.INNER, 60)


def interior(doc=None, width=qw.INNER):
    return qw.compose(doc or document(GRANT, grant=GRANT), PLAIN, width)


# --------------------------------------------------------------------------- #
# The interior
# --------------------------------------------------------------------------- #


@given(value=values, width=widths)
def test_the_interior_fits_the_width_it_was_given(value, width):
    for plain, _ in interior(document(value, grant=GRANT), width):
        assert len(plain) <= width


@given(value=values)
def test_the_interior_is_ascii_but_for_the_one_measured_glyph(value):
    """A right-hand border built from rules or blocks lands somewhere different
    on every row, because the launcher's font does not draw them one cell wide.
    The middot was measured at exactly one cell; nothing else non-ASCII is."""
    for plain, _ in interior(document(value, grant=GRANT)):
        assert set(plain) - set(map(chr, range(128))) <= {"·"}


@given(value=values)
def test_no_row_is_padded_out_with_spaces(value):
    """Some widgets draw a run of trailing spaces as a stray highlighted band."""
    for plain, _ in interior(document(value, grant=GRANT)):
        assert plain == plain.rstrip()


def test_the_box_says_what_is_left_against_todays_pool():
    # Three different figures on purpose: with the pool equal to the grant, a
    # row that printed the wrong one would still read as right.
    doc = document(GRANT // 2, grant=GRANT, pool=3 * GRANT)
    rows = [plain for plain, _ in interior(doc)]
    assert any(qw.size(doc["today"]["remainder_bytes"]) in row for row in rows)
    assert any(qw.size(doc["today"]["pool_bytes"]) in row for row in rows)


def test_the_box_names_the_reserve_in_bytes_and_in_money():
    doc = document(GRANT, grant=GRANT, credits=3.5)
    rows = [plain for plain, _ in interior(doc)]
    assert any(qw.size(doc["reserve"]["bytes"]) in row for row in rows)
    assert any(qw.money(3.5) in row for row in rows)


def test_the_head_carries_a_stale_reading_the_way_the_face_does():
    doc = document(GRANT, grant=GRANT, age=7200, live=False)
    assert "ago" in interior(doc)[0][0]


def test_the_head_carries_an_offline_reading():
    head = interior(document(GRANT, grant=GRANT, online=False))[0][0]
    assert head.endswith("offline")


@pytest.mark.parametrize(
    "age, said", [(0, False), (89.4, False), (90, True), (7200, True)]
)
def test_the_head_and_the_status_line_agree_on_when_a_reading_is_old(age, said):
    doc = document(GRANT, grant=GRANT, age=age, live=False)
    assert ("ago" in interior(doc)[0][0]) is said
    assert ("ago" in qw.render_line(doc, PLAIN)) is said


def test_a_current_reading_leaves_the_head_alone():
    head = interior(document(GRANT, grant=GRANT))[0][0]
    assert head.strip() == "ZWANA DATA"


# --------------------------------------------------------------------------- #
# The bar
# --------------------------------------------------------------------------- #

BAR = re.compile(r"\[([=·]*)\]\s*(\d+)%")


def bar_row(doc, width=qw.INNER):
    for plain, _ in interior(doc, width):
        found = BAR.search(plain)
        if found:
            return found.group(1), int(found.group(2))
    raise AssertionError("no bar was drawn")


@given(value=values, width=widths)
def test_the_bar_is_the_same_length_however_full_it_is(value, width):
    empty, _ = bar_row(document(0, grant=GRANT), width)
    some, _ = bar_row(document(value, grant=GRANT, pool=max(value, GRANT)), width)
    assert len(some) == len(empty)
    assert len(empty) >= 4


def test_the_frames_calibrated_measurements():
    """The box is drawn for a terminal and for the widget's wider face; the
    indent is what keeps the text off One UI's rounded corner."""
    assert qw.INNER == 30
    assert qw.INDENT == 3


@pytest.mark.parametrize("width", [qw.INNER, 40, 60])
def test_the_bar_spans_the_row_rather_than_sitting_in_a_corner_of_it(width):
    """It is the widget's one picture; a stub of a bar reads as a full one."""
    bar, _ = bar_row(document(GRANT // 2, grant=GRANT), width)
    assert len(bar) >= width // 2


def test_the_bar_is_drawn_against_the_smallest_pool_there_can_be():
    bar, pct = bar_row(document(1, grant=0, pool=1))
    assert pct == 0
    assert bar.count("=") == 0


@pytest.mark.parametrize("share", [1.0, 0.5, 0.2, 0.05])
def test_the_bar_is_painted_by_how_much_is_left(share):
    pool = 10**6
    doc = document(int(pool * share), grant=pool, pool=pool)
    styled = "".join(styled for _, styled in qw.compose(doc, qw.Paint(True), qw.INNER))
    assert qw.grade(share).split(";")[-1] in ANSI_CODES.findall(styled)


@given(value=values)
def test_the_filled_run_is_the_share_that_has_gone(value):
    doc = document(value, grant=GRANT, pool=max(value, GRANT))
    bar, pct = bar_row(doc)
    used = doc["today"]["used_bytes"]
    pool = doc["today"]["pool_bytes"]
    filled = bar.count("=")
    assert filled + bar.count("·") == len(bar)
    assert abs(filled / len(bar) - used / pool) <= 0.5 / len(bar) + 1e-9
    assert abs(pct - used / pool * 100) <= 0.5


def test_an_untouched_pool_draws_an_empty_bar():
    bar, pct = bar_row(document(GRANT, grant=GRANT))
    assert bar.count("=") == 0
    assert pct == 0


def test_a_spent_pool_draws_a_full_bar():
    bar, pct = bar_row(document(0, grant=GRANT))
    assert bar.count("·") == 0
    assert pct == 100


@given(a=values, b=values)
def test_spending_more_never_shortens_the_filled_run(a, b):
    pool = max(a, b, GRANT)
    left = bar_row(document(min(a, b), grant=GRANT, pool=pool))[0].count("=")
    right = bar_row(document(max(a, b), grant=GRANT, pool=pool))[0].count("=")
    assert left >= right


# --------------------------------------------------------------------------- #
# The top-up row
# --------------------------------------------------------------------------- #


def topup_row(doc, width=qw.INNER):
    return next(plain for plain, _ in interior(doc, width) if plain.lstrip().startswith("+"))


def test_the_top_up_row_names_the_grant_and_when_it_lands():
    doc = document(GRANT, grant=GRANT)
    row = topup_row(doc)
    stamp = qw.dt.datetime.fromisoformat(doc["reset"]["local"]).strftime("%H:%M")
    assert qw.size(GRANT) in row
    assert stamp in row


def test_the_countdown_gives_way_before_the_row_runs_past_the_frame():
    """It restates what the clock time already says, so it is the part that
    can go; the named day and the time cannot."""
    doc = document(GRANT, grant=GRANT)
    narrow = topup_row(doc, qw.INNER)
    wide = topup_row(doc, 60)
    assert len(narrow) <= qw.INNER
    assert len(wide) >= len(narrow)
    assert qw.countdown(qw.dt.timedelta(seconds=doc["reset"]["seconds_until"])) in wide


# --------------------------------------------------------------------------- #
# The frames
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("frame", ["corners", "box", "none"])
def test_a_frame_never_makes_a_row_wider_than_the_frame_is(frame):
    drawn = qw.render(document(GRANT, grant=GRANT), PLAIN, frame=frame).split("\n")
    assert all(len(line) <= qw.INNER + 2 for line in drawn)


def test_the_corner_frame_has_four_marks_and_no_row_border():
    drawn = qw.render(document(GRANT, grant=GRANT), PLAIN, frame="corners").split("\n")
    assert "╭" in drawn[0] and "╮" in drawn[0]
    assert "╰" in drawn[-1] and "╯" in drawn[-1]
    for line in drawn[1:-1]:
        assert not BOX_GLYPHS & set(line)


@pytest.mark.parametrize("width", [qw.INNER, 40, 60])
def test_the_corner_marks_sit_at_the_two_ends_of_the_width_asked_for(width):
    drawn = qw.render(
        document(GRANT, grant=GRANT), PLAIN, width=width, frame="corners"
    ).split("\n")
    assert len(drawn[0]) == width
    assert len(drawn[-1]) == width


def test_the_full_box_borders_every_row_at_one_width():
    drawn = qw.render(document(GRANT, grant=GRANT), PLAIN, frame="box").split("\n")
    assert len({len(line) for line in drawn}) == 1
    for line in drawn[1:-1]:
        assert line.startswith("│") and line.endswith("│")


def test_no_frame_draws_no_frame():
    drawn = qw.render(document(GRANT, grant=GRANT), PLAIN, frame="none")
    assert not BOX_GLYPHS & set(drawn)


@pytest.mark.parametrize("frame", ["corners", "none"])
def test_a_blank_row_stays_blank_even_with_a_margin(frame):
    drawn = qw.render(
        document(GRANT, grant=GRANT), PLAIN, frame=frame, margin=4
    ).split("\n")
    assert any(line == "" for line in drawn)
    assert all(line == line.rstrip() for line in drawn)


def test_a_margin_moves_the_whole_face_across():
    doc = document(GRANT, grant=GRANT)
    flush = qw.render(doc, PLAIN, frame="none").split("\n")
    shifted = qw.render(doc, PLAIN, frame="none", margin=4).split("\n")
    for before, after in zip(flush, shifted, strict=True):
        assert after == ("" if not before else "    " + before)


def test_colour_is_only_ever_added_around_the_same_text():
    doc = document(GRANT // 2, grant=GRANT)
    assert uncoloured(qw.render(doc, qw.Paint(True))) == qw.render(doc, PLAIN)


# --------------------------------------------------------------------------- #
# --line
# --------------------------------------------------------------------------- #


@given(value=values)
def test_the_status_line_is_one_line_carrying_all_three_figures(value):
    doc = document(value, grant=GRANT, pool=max(value, GRANT))
    line = qw.render_line(doc, PLAIN)
    assert "\n" not in line
    assert qw.size(doc["today"]["remainder_bytes"]) in line
    assert qw.size(doc["today"]["pool_bytes"]) in line
    assert qw.size(doc["free"]["grant_bytes"]) in line


def test_the_status_line_marks_a_stale_reading_and_nothing_else():
    fresh = qw.render_line(document(GRANT, grant=GRANT), PLAIN)
    seconds_old = qw.render_line(document(GRANT, grant=GRANT, age=30, live=False), PLAIN)
    stale = qw.render_line(document(GRANT, grant=GRANT, age=7200, live=False), PLAIN)
    assert "ago" not in fresh
    assert "ago" not in seconds_old
    assert "ago" in stale


# --------------------------------------------------------------------------- #
# The edges: the frame drawing nothing, the named day, the head's room
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("frame", ["corners", "box", "none"])
def test_every_frame_draws_the_reading_inside_it(frame):
    """A frame that lost its contents still looks like a frame."""
    doc = document(GRANT // 2, grant=GRANT, pool=3 * GRANT)
    drawn = qw.render(doc, PLAIN, frame=frame)
    assert qw.size(doc["today"]["remainder_bytes"]) in drawn
    assert qw.size(doc["today"]["pool_bytes"]) in drawn
    assert qw.size(doc["reserve"]["bytes"]) in drawn


def test_the_corner_marks_stand_off_the_face_by_a_blank_row():
    drawn = qw.render(document(GRANT, grant=GRANT), PLAIN, frame="corners").split("\n")
    assert drawn[1] == ""
    assert drawn[-2] == ""


@pytest.mark.parametrize("width", [qw.INNER, 40])
def test_the_head_keeps_a_stale_note_inside_the_frame(width):
    """The head is the one row laid out against the width from both ends."""
    doc = document(GRANT, grant=GRANT, age=7200, live=False)
    rows = interior(doc, width)
    assert all(len(plain) <= width for plain, _ in rows)
    assert rows[0][0].strip().startswith("ZWANA DATA")
    assert rows[0][0].rstrip().endswith(qw.since(7200))


def test_a_reset_later_today_is_not_named_and_one_after_midnight_is(clock):
    doc = document(GRANT, grant=GRANT)
    later_today = qw.dt.datetime.now().astimezone().replace(microsecond=0)
    doc["reset"]["local"] = later_today.isoformat()
    assert "today" not in topup_row(doc)

    doc["reset"]["local"] = (later_today + qw.dt.timedelta(days=1)).isoformat()
    assert "tmrw" in topup_row(doc)


def test_the_status_line_states_three_different_figures():
    doc = document(GRANT // 2, grant=GRANT, pool=3 * GRANT)
    line = qw.render_line(doc, PLAIN)
    for figure in (
        qw.size(doc["today"]["remainder_bytes"]),
        qw.size(doc["today"]["pool_bytes"]),
        qw.size(doc["free"]["grant_bytes"]),
    ):
        assert figure in line
    assert "00:00" in line
    assert qw.countdown(qw.dt.timedelta(seconds=doc["reset"]["seconds_until"])) in line
