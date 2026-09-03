"""The widget face: one big number that has to fit the tile at every magnitude.

Overflowing is not an error here, it is a widget with a line clipped off and
nothing to say so — which is why the checks are about *fitting* and about what
survives the squeeze, rather than about the exact face at one figure.
"""

from __future__ import annotations

import re

import pytest
from conftest import GRANT, document, parse_size
from hypothesis import given
from hypothesis import strategies as st

import quota_widget as qw

PLAIN = qw.Paint(False)

#: Magnitudes worth naming: every unit threshold, the values that gain a digit,
#: the nightly grant, and the figure the tile documentation is calibrated on.
MAGNITUDES = [
    0, 1, 512, 1023, 1024, 1024**2 - 1, 1024**2, 9 * 1024**2 + 1000,
    10 * 1024**2, 100 * 1024**2, GRANT, 1024**3 - 1, 1024**3,
    int(1.68 * 1024**3), int(9.999 * 1024**3), 10 * 1024**3,
    int(99.999 * 1024**3), 100 * 1024**3, 1024**4, 2**45,
]

#: The figures the face is asked to draw. Above this the tile stops being a
#: data readout and the small print has nowhere left to give — 32 TiB is some
#: four decades of nightly grants.
values = st.integers(0, 2**45)


def face(remainder: int, width: int = qw.TILE, **over):
    over.setdefault("grant", GRANT)
    doc = document(remainder, **over)
    return doc, qw.compose_tile(doc, PLAIN, width)


# --------------------------------------------------------------------------- #
# The glyphs
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("char", sorted(qw.GLYPHS))
def test_every_glyph_is_a_rectangle_of_ascii(char):
    rows = qw.GLYPHS[char]
    assert len(rows) == qw.GLYPH_ROWS
    assert len({len(row) for row in rows}) == 1
    assert all(row.isascii() for row in rows)


def test_the_digits_and_the_point_are_all_the_face_ever_needs():
    assert set("0123456789.") <= set(qw.GLYPHS)


@given(text=st.text(alphabet="0123456789.", min_size=1, max_size=8))
def test_the_width_a_figure_needs_is_the_width_it_takes(text):
    """``compose_tile`` budgets with :func:`glyph_width` and then measures the
    rows :func:`big` actually returned; if those two disagree the face is laid
    out against a figure that is not the one drawn."""
    art = qw.big(text)
    assert len(art) == qw.GLYPH_ROWS
    assert len({len(row) for row in art}) == 1
    assert len(art[0]) == qw.glyph_width(text)


def test_no_figure_at_all_takes_no_room():
    assert qw.glyph_width("") == 0
    assert qw.big("") == [""] * qw.GLYPH_ROWS


@given(text=st.text(alphabet="0123456789.", min_size=1, max_size=6))
def test_a_longer_figure_is_never_narrower(text):
    assert qw.glyph_width(text + "0") > qw.glyph_width(text)


# --------------------------------------------------------------------------- #
# face_value(): precision is whatever fits
# --------------------------------------------------------------------------- #


@given(value=values, budget=st.integers(0, 30))
def test_the_drawn_figure_fits_unless_nothing_would(value, budget):
    """Only the coarsest spelling may exceed the budget, and only on a tile too
    narrow to hold even that."""
    text, _unit = qw.face_value(value, budget)
    coarsest, _ = qw.face_value(value, 0)
    assert qw.glyph_width(text) <= budget or text == coarsest


@given(value=values, budget=st.integers(0, 40))
def test_the_drawn_figure_never_lies_whatever_the_budget(value, budget):
    text, unit = qw.face_value(value, budget)
    read, tolerance = parse_size(f"{text} {unit}")
    assert abs(read - value) <= tolerance


@given(value=values, budget=st.integers(0, 40))
def test_a_smaller_budget_never_buys_a_wider_figure(value, budget):
    wide = qw.glyph_width(qw.face_value(value, budget + 1)[0])
    narrow = qw.glyph_width(qw.face_value(value, budget)[0])
    assert narrow <= wide


@pytest.mark.parametrize(
    "value, unit",
    [(0, "KiB"), (1023, "KiB"), (1024**2 - 1, "KiB"), (1024**2, "MiB"),
     (1024**3 - 1, "MiB"), (1024**3, "GiB"), (2**45, "GiB")],
)
def test_the_unit_follows_the_magnitude(value, unit):
    assert qw.face_value(value, qw.TILE)[1] == unit


@pytest.mark.parametrize(
    "value, drawn",
    [(int(1.5 * 1024**2), "1.5"), (int(9.5 * 1024**2), "9.5"),
     (int(10.5 * 1024**2), "10"), (100 * 1024**2, "100")],
)
def test_below_ten_mebibytes_the_face_keeps_a_decimal_and_above_it_does_not(
    value, drawn
):
    """Ten is where a decimal stops being worth a column: 9.5 MiB is a
    different figure from 10 MiB, 100.5 is not from 100."""
    assert qw.face_value(value, 30)[0] == drawn


def test_a_figure_that_rounds_into_another_digit_gives_up_a_decimal():
    budget = qw.TILE - qw.FACE_GAP - qw.FACE_SPINE
    two_digits, _ = qw.face_value(int(9.999 * 1024**3), budget)
    assert qw.glyph_width(two_digits) <= budget
    assert float(two_digits) >= 10


# --------------------------------------------------------------------------- #
# The face as a whole
# --------------------------------------------------------------------------- #


@given(value=values)
def test_the_face_is_five_rows_and_never_wider_than_the_tile(value):
    _, rows = face(value)
    assert len(rows) == qw.GLYPH_ROWS
    for plain, _styled in rows:
        assert len(plain) <= qw.TILE
        assert plain.isascii()
        assert "\n" not in plain
        assert plain == plain.rstrip()


def test_the_calibrated_measurements_are_the_ones_the_docs_were_written_to():
    """These are measured with ``--probe`` against the real launcher, not
    chosen — so they are meant to be re-measured, and a change to one is a
    change to what ``docs/quota-tile.md`` and the module header describe."""
    assert qw.TILE == 35
    assert qw.TILE_LINES == 6
    assert qw.GLYPH_ROWS == 5
    assert (qw.TILE_MARGIN, qw.TILE_TOP) == (1, 1)
    assert qw.GLYPH_GAP == 1
    assert qw.FACE_GAP == 3
    # The width of "+762 MiB 05:30": the longest top-up row that still carries
    # both facts, which is the room the figure is not allowed to grow into.
    assert len("+762 MiB 05:30") == qw.FACE_SPINE


def test_a_big_margin_still_leaves_the_figure_inside_the_tile():
    """The nudge is a request, but it never pushes the face into a wrap."""
    doc = document(500, grant=GRANT)
    drawn = qw.render_tile(doc, PLAIN, qw.TILE, margin=99).split("\n")
    assert max(len(line) for line in drawn) == qw.TILE - 1
    assert drawn[1].startswith(" ")


def test_the_face_and_its_margin_leave_the_tile_a_spare_row():
    """Five rows of face in a tile of six, which is what fixes the layout."""
    assert qw.GLYPH_ROWS + qw.TILE_TOP <= qw.TILE_LINES


@pytest.mark.parametrize("value", MAGNITUDES)
@pytest.mark.parametrize("width", [30, 32, qw.TILE, 40, 48])
def test_the_face_fits_every_width_at_every_magnitude(value, width):
    _, rows = face(value, width)
    assert max(len(plain) for plain, _ in rows) <= width


@given(value=values, margin=st.integers(0, 20))
def test_the_margin_gives_way_to_a_figure_that_needs_the_columns(value, margin):
    doc = document(value, grant=GRANT)
    drawn = qw.render_tile(doc, PLAIN, qw.TILE, margin).split("\n")
    assert len(drawn) == qw.GLYPH_ROWS
    assert all(len(line) <= qw.TILE for line in drawn)


@given(value=values)
def test_the_small_print_hangs_off_the_figure_in_one_column(value):
    """Every row of the figure is the same width, so the print beside it lines
    up with the figure rather than with the tile."""
    _, rows = face(value)
    text, _ = qw.face_value(value, qw.TILE - qw.FACE_GAP - qw.FACE_SPINE)
    art = qw.big(text)
    for (plain, _styled), line in zip(rows, art, strict=True):
        if plain == line.rstrip():
            continue
        assert plain.startswith(line + " " * qw.FACE_GAP)
        assert plain[len(line) + qw.FACE_GAP] != " "


@given(value=values)
def test_the_reset_time_survives_every_squeeze(value):
    """It is the one fact on the face that cannot be worked out from the rest,
    so the top-up amount gives way before the clock does."""
    doc, rows = face(value)
    stamp = qw.dt.datetime.fromisoformat(doc["reset"]["local"]).strftime("%H:%M")
    assert stamp in rows[3][0]


@given(value=values)
def test_the_unit_is_always_said_beside_the_figure(value):
    doc, rows = face(value)
    unit = qw.face_value(value, qw.TILE - qw.FACE_GAP - qw.FACE_SPINE)[1]
    assert unit in rows[1][0]


def test_the_top_up_row_has_room_for_both_facts_at_the_documented_figures():
    """``+763 MiB 00:00`` is what :data:`quota_widget.FACE_SPINE` is the width
    of; at the grant and a GiB-scale remainder the tile keeps both."""
    doc, rows = face(int(1.68 * 1024**3))
    assert "+" in rows[3][0]
    assert qw.size(doc["free"]["grant_bytes"]) in rows[3][0]


# --------------------------------------------------------------------------- #
# A reading that overstates has to say so on the face
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("value", MAGNITUDES)
def test_a_stale_reading_is_drawn_and_not_merely_fitted(value):
    _, rows = face(value, age=7200, live=False)
    assert "ago" in rows[0][0]
    assert max(len(plain) for plain, _ in rows) <= qw.TILE


@pytest.mark.parametrize("value", MAGNITUDES)
def test_an_offline_reading_is_drawn_and_not_merely_fitted(value):
    _, rows = face(value, online=False)
    assert rows[0][0].endswith("offline")
    assert max(len(plain) for plain, _ in rows) <= qw.TILE


def test_a_current_reading_says_nothing_on_that_row():
    _, rows = face(GRANT, age=5, live=False)
    assert rows[0][0].rstrip() == qw.big(qw.face_value(GRANT, 18)[0])[0].rstrip()


@pytest.mark.parametrize(
    "age, said", [(0, False), (89.4, False), (90, True), (7200, True)]
)
def test_the_face_starts_saying_so_where_the_age_stops_reading_as_current(age, said):
    """A reading seconds old is simply current; the threshold is the one
    ``since`` itself changes unit at, so the face never says ``0m ago``."""
    _, rows = face(GRANT, age=age, live=False)
    assert ("ago" in rows[0][0]) is said


def test_a_live_reading_of_any_age_is_not_stale():
    """The age is how long ago the portal answered, not how long ago it was
    asked, so a slow live read is still live."""
    _, rows = face(GRANT, age=9999, live=True)
    assert "ago" not in rows[0][0]


def test_paid_data_is_named_rather_than_read_as_free():
    paid = 2 * 1024**3
    _, rows = face(GRANT + paid, pool=GRANT + paid)
    assert rows[4][0].endswith(f"{qw.size(paid)} paid")


def test_nothing_is_said_about_paid_data_when_there_is_none():
    _, rows = face(GRANT)
    assert rows[4][0].strip() == qw.big(qw.face_value(GRANT, 18)[0])[4].strip()


# --------------------------------------------------------------------------- #
# Colour
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("share", [1.0, 0.5, 0.2, 0.05])
def test_the_figure_is_painted_by_how_much_is_left(clock, share):
    """The colour is the face's own version of the icon: it has to follow the
    share, not merely be some colour."""
    pool = 10**6
    doc = document(int(pool * share), grant=pool, pool=pool)
    styled = qw.compose_tile(doc, qw.Paint(True), qw.TILE)[0][1]
    assert f"\033[{qw.grade(share)}m" in styled


def test_the_figure_and_its_print_are_painted_apart(clock):
    doc = document(GRANT // 2, grant=GRANT)
    rows = qw.compose_tile(doc, qw.Paint(True), qw.TILE)
    plain, styled = rows[1]
    assert plain != styled
    assert qw.grade(doc["today"]["remainder_bytes"] / doc["today"]["pool_bytes"]) in styled
    assert "\033[90m" in styled


# --------------------------------------------------------------------------- #
# The edges: the exact fit, the smallest pool, the share itself
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("value", MAGNITUDES)
def test_the_drawn_figure_reads_back_as_the_figure_it_was_given(value):
    text, unit = qw.face_value(value, qw.TILE - qw.FACE_GAP - qw.FACE_SPINE)
    read, tolerance = parse_size(f"{text} {unit}")
    assert abs(read - value) <= tolerance


def test_a_figure_that_exactly_fills_the_budget_keeps_its_precision():
    """The rung is taken when it fits, not when it fits with room to spare."""
    value = int(1.68 * 1024**3)
    exact = qw.glyph_width("1.68")
    assert qw.face_value(value, exact)[0] == "1.68"
    assert qw.face_value(value, exact - 1)[0] == "1.7"


def test_the_top_up_row_keeps_both_facts_on_a_row_that_exactly_holds_them():
    """35 columns cannot produce the exact fit — the figure's widths come in
    steps of two — so it is checked at the width that can."""
    doc, rows = face(1024**4, width=32)
    assert qw.size(doc["free"]["grant_bytes"]) in rows[3][0]
    assert len(rows[3][0]) == 32


SHARE = re.compile(r"(\d+)%")


@pytest.mark.parametrize(
    "remainder, pool, share",
    [(0, 1000, 0), (1, 1, 100), (500, 1000, 50), (999, 1000, 100), (1000, 1000, 100),
     (1, 1000, 0), (250, 1000, 25), (80, 1000, 8)],
)
def test_the_share_is_the_figure_against_todays_pool(remainder, pool, share):
    """The number on the face has a scale attached rather than floating free."""
    _, rows = face(remainder, grant=pool, pool=pool)
    assert int(SHARE.search(rows[2][0]).group(1)) == share


def test_the_smallest_possible_pool_still_reads_as_all_of_it():
    _, rows = face(1, grant=0, pool=1)
    assert "100%" in rows[2][0]


def test_a_single_byte_of_paid_data_is_still_named():
    _, rows = face(GRANT + 1, grant=GRANT, pool=GRANT + 1)
    assert "paid" in rows[4][0]


def test_the_reset_time_on_the_face_is_the_reset(clock):
    """The clock and the zone are pinned by the fixture, so this is the real
    local spelling of the next top-up and not a restatement of the code."""
    _, rows = face(int(1.68 * 1024**3))
    assert "00:00" in rows[3][0]


def test_the_face_is_drawn_flush_when_no_margin_is_asked_for():
    drawn = qw.render_tile(document(GRANT, grant=GRANT), PLAIN).split("\n")
    assert not drawn[0].startswith(" ")
