"""The Quick Settings tile: four lines Tasker addresses by position.

The order is the interface — ``%stdout1``..``%stdout4`` after a Variable Split
— so reordering them silently relabels the tile rather than failing anything.
That, the width budgets at every size, and a reading that overstates having to
say so are what is pinned here.
"""

from __future__ import annotations

import re

import pytest
from conftest import GRANT, document, parse_size
from hypothesis import given
from hypothesis import strategies as st

import quota_widget as qw

#: The magnitudes the tile is calibrated for. Above 8 TiB the smallest label
#: budget runs out of rungs; the portal's day is three orders below that.
values = st.integers(0, 2**43)

MAGNITUDES = [
    0, 1, 1023, 1024, 1024**2 - 1, 1024**2, 10 * 1024**2, GRANT,
    1024**3 - 1, 1024**3, int(1.68 * 1024**3), 10 * 1024**3, 100 * 1024**3,
    1024**4, 2**43,
]

SIZES = sorted(qw.QS_SIZES)


# --------------------------------------------------------------------------- #
# The four lines, in that order
# --------------------------------------------------------------------------- #


@given(value=values)
def test_the_answer_is_always_four_lines_in_the_documented_order(value):
    doc = document(value, grant=GRANT)
    fields = qw.compose_qs(doc)
    label, status, state, level = fields
    drawn = qw.render_qs(doc).split("\n")
    assert len(fields) == 4
    assert drawn == [label, status, state, level]


@given(value=values, size=st.sampled_from(SIZES))
def test_no_field_can_be_mistaken_for_two(value, size):
    """The lines cross a shell, the Termux:Tasker plugin and Tasker's own
    variable handling before anything draws them, and the tile script refuses a
    separator that appears in the reading itself."""
    doc = document(value, grant=GRANT)
    for field in qw.compose_qs(doc, *qw.QS_SIZES[size]):
        assert "\n" not in field
        assert "|" not in field
        assert field.isascii()


@given(value=values, size=st.sampled_from(SIZES))
def test_the_state_is_never_the_greyed_out_one(value, size):
    """Android's UNAVAILABLE cannot be tapped, and a tap is how a tile with no
    reading behind it gets one."""
    doc = document(value, grant=GRANT)
    state = qw.compose_qs(doc, *qw.QS_SIZES[size])[2]
    assert state in ("active", "inactive")


@given(value=values, grant=st.integers(0, 2**32))
def test_active_means_there_is_still_free_data(value, grant):
    doc = document(value, grant=grant)
    state = qw.compose_qs(doc)[2]
    assert (state == "active") is (doc["free"]["left_bytes"] > 0)


@given(value=values)
def test_the_level_is_the_word_for_the_colour_the_face_would_use(value):
    doc = document(value, grant=GRANT)
    level = qw.compose_qs(doc)[3]
    share = doc["today"]["remainder_bytes"] / max(1, doc["today"]["pool_bytes"])
    assert level == qw.QS_LEVELS[qw.grade(share)]
    assert level in ("ok", "low", "critical")


def test_the_level_words_are_the_ones_the_icons_are_wired_to():
    assert set(qw.QS_LEVELS.values()) == {"ok", "low", "critical"}
    assert qw.QS_UNKNOWN[3] == "unknown"
    assert qw.QS_UNKNOWN[3] not in qw.QS_LEVELS.values()


def test_the_tile_with_no_reading_has_the_same_shape_as_any_other():
    label, status, state, level = qw.QS_UNKNOWN
    assert len(qw.QS_UNKNOWN) == 4
    assert label.strip()
    assert status.strip()
    assert state in ("active", "inactive")
    assert level.strip()
    assert all(field.isascii() and "\n" not in field for field in qw.QS_UNKNOWN)


# --------------------------------------------------------------------------- #
# qs_size(): the figure at whatever precision the room allows
# --------------------------------------------------------------------------- #


@given(value=values, room=st.integers(0, 20))
def test_the_label_never_lies_whatever_the_room(value, room):
    read, tolerance = parse_size(qw.qs_size(value, room))
    assert abs(read - value) <= tolerance


@given(value=values, room=st.integers(0, 20))
def test_the_label_fits_unless_no_spelling_would(value, room):
    text = qw.qs_size(value, room)
    shortest = qw.qs_size(value, 0)
    assert len(text) <= room or text == shortest


@given(value=values, room=st.integers(0, 20))
def test_more_room_never_buys_a_shorter_label(value, room):
    assert len(qw.qs_size(value, room + 1)) >= len(qw.qs_size(value, room))


@given(value=values)
def test_the_widest_spelling_is_the_one_the_widget_and_the_line_use(value):
    """An unclipped tile has to read exactly like the home screen does."""
    assert qw.qs_size(value, 99) == qw.size(value)


@pytest.mark.parametrize("value", MAGNITUDES)
@pytest.mark.parametrize("size", SIZES)
def test_the_label_fits_every_named_tile_at_every_magnitude(value, size):
    label_width, _ = qw.QS_SIZES[size]
    doc = document(value, grant=GRANT)
    assert len(qw.compose_qs(doc, *qw.QS_SIZES[size])[0]) <= label_width


# --------------------------------------------------------------------------- #
# The subtitle ladder
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("value", MAGNITUDES)
@pytest.mark.parametrize("size", SIZES)
@pytest.mark.parametrize(
    "reading", [{}, {"age": 7200, "live": False}, {"online": False}],
    ids=["current", "stale", "offline"],
)
def test_the_subtitle_fits_every_named_tile_at_every_magnitude(value, size, reading):
    _, status_width = qw.QS_SIZES[size]
    doc = document(value, grant=GRANT, **reading)
    assert len(qw.compose_qs(doc, *qw.QS_SIZES[size])[1]) <= status_width


@given(value=values, width=st.integers(1, 40))
def test_the_subtitle_fits_unless_no_rung_would(value, width):
    doc = document(value, grant=GRANT)
    status = qw.compose_qs(doc, 10, width)[1]
    shortest = qw.compose_qs(doc, 10, 1)[1]
    assert len(status) <= width or status == shortest


def test_a_tile_too_narrow_for_any_rung_gets_the_shortest_one_there_is():
    """Not merely *a* rung: half a phrase on a tile is worse than the terse
    one that was written to be the last resort."""
    doc = document(int(1.68 * 1024**3), grant=GRANT)
    rungs = {qw.compose_qs(doc, 10, width)[1] for width in range(1, 60)}
    assert qw.compose_qs(doc, 10, 1)[1] == min(rungs, key=len)


@given(value=values, width=st.integers(1, 40))
def test_a_wider_tile_is_never_told_less(value, width):
    """The ladder is climbed as well as descended: a tile dragged wide gets
    told the pool as well, rather than leaving the room blank."""
    doc = document(value, grant=GRANT)
    assert len(qw.compose_qs(doc, 10, width + 1)[1]) >= len(
        qw.compose_qs(doc, 10, width)[1]
    )


@given(value=values)
def test_the_smallest_tile_has_no_subtitle_rather_than_half_a_phrase(value):
    doc = document(value, grant=GRANT)
    assert qw.compose_qs(doc, 5, 0)[1] == ""
    assert qw.QS_SIZES["small"][1] == 0


@pytest.mark.parametrize("width", [1, 5, 10, 16, 34, 60])
def test_a_stale_reading_says_so_at_every_width_that_has_a_subtitle(width):
    doc = document(int(1.68 * 1024**3), grant=GRANT, age=7200, live=False)
    status = qw.compose_qs(doc, 10, width)[1]
    assert qw.since(doc["reading"]["age_seconds"]) in status


@pytest.mark.parametrize(
    "age, said", [(0, False), (89.4, False), (90, True), (7200, True)]
)
def test_the_subtitle_starts_saying_so_at_the_same_age_the_face_does(age, said):
    doc = document(int(1.68 * 1024**3), grant=GRANT, age=age, live=False)
    assert ("ago" in qw.compose_qs(doc, *qw.QS_SIZES["medium"])[1]) is said


@pytest.mark.parametrize("width", [1, 5, 10, 16, 34, 60])
def test_an_offline_reading_says_so_at_every_width_that_has_a_subtitle(width):
    doc = document(int(1.68 * 1024**3), grant=GRANT, online=False)
    # It leads the subtitle: what the figure cannot be trusted for outranks
    # everything the subtitle would otherwise say.
    assert qw.compose_qs(doc, 10, width)[1].startswith("offline")


def test_a_current_reading_spends_the_room_on_the_share_instead():
    doc = document(int(1.68 * 1024**3), grant=GRANT)
    status = qw.compose_qs(doc, *qw.QS_SIZES["large"])[1]
    assert "%" in status
    assert "ago" not in status
    assert "offline" not in status


@pytest.mark.parametrize("size", SIZES)
def test_the_reset_time_is_kept_ahead_of_the_share(size):
    """It is the one fact on the tile that cannot be worked out from the rest."""
    label_width, status_width = qw.QS_SIZES[size]
    doc = document(int(1.68 * 1024**3), grant=GRANT)
    status = qw.compose_qs(doc, label_width, status_width)[1]
    stamp = qw.dt.datetime.fromisoformat(doc["reset"]["local"]).strftime("%H:%M")
    assert status == "" or stamp in status


def test_the_named_sizes_are_the_ones_the_documentation_tabulates():
    """``docs/quota-tile.md`` names these three and what each is for; they are
    budgets measured against a proportional font, so they are meant to be
    re-measured with ``--qs-probe`` rather than adjusted by taste."""
    assert qw.QS_SIZES == {"small": (5, 0), "medium": (10, 16), "large": (12, 34)}
    assert (qw.QS_LABEL, qw.QS_STATUS) == (10, 16)


def test_the_widths_climb_with_the_tile_size():
    small, medium, large = (qw.QS_SIZES[name] for name in ("small", "medium", "large"))
    assert small[0] < medium[0] <= large[0]
    assert small[1] < medium[1] < large[1]
    assert qw.QS_SIZES["medium"] == (qw.QS_LABEL, qw.QS_STATUS)


# --------------------------------------------------------------------------- #
# The ruler
# --------------------------------------------------------------------------- #


@given(label=st.integers(0, 40), status=st.integers(0, 40))
def test_the_probe_is_longer_than_any_tile_it_is_measuring(label, status):
    """A ruler that fits tells you nothing except that it fits."""
    lines = qw.qs_probe(label, status).split("\n")
    assert len(lines) == 4
    assert len(lines[0]) > label
    assert len(lines[1]) > status


def test_the_probe_keeps_the_shape_the_tile_is_wired_to():
    lines = qw.qs_probe(*qw.QS_SIZES["medium"]).split("\n")
    assert lines[2] in ("active", "inactive")
    assert lines[3] in qw.QS_LEVELS.values()


def test_the_probe_is_numbered_so_the_last_visible_mark_can_be_read_off():
    first = qw.qs_probe(10, 30).split("\n")[0]
    assert first.startswith("----+----1")
    assert set(first) <= set("-+0123456789")


# --------------------------------------------------------------------------- #
# The edges: exact fits, the unit thresholds, the share, the documented words
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("value", MAGNITUDES)
def test_an_unclipped_label_reads_exactly_like_the_widget_does(value):
    """Checked at the thresholds themselves: a property spread over eight
    tebibytes lands on none of them."""
    assert qw.qs_size(value, 99) == qw.size(value)


@pytest.mark.parametrize("value", MAGNITUDES)
def test_the_label_reads_back_as_the_figure_it_was_given(value):
    for room in (5, 8, 10, 12):
        read, tolerance = parse_size(qw.qs_size(value, room))
        assert abs(read - value) <= tolerance


@pytest.mark.parametrize("value", MAGNITUDES)
def test_a_label_that_exactly_fills_the_room_keeps_its_precision(value):
    """The rung is taken when it fits, not when it fits with room to spare."""
    widest = qw.size(value)
    assert qw.qs_size(value, len(widest)) == widest


def test_a_subtitle_that_exactly_fills_the_tile_keeps_its_rung():
    doc = document(int(1.68 * 1024**3), grant=GRANT)
    status = qw.compose_qs(doc, 10, 34)[1]
    assert qw.compose_qs(doc, 10, len(status))[1] == status
    assert len(qw.compose_qs(doc, 10, len(status) - 1)[1]) < len(status)


SHARE = re.compile(r"(\d+)%")


@pytest.mark.parametrize(
    "remainder, pool, share",
    [(0, 1000, 0), (1, 1, 100), (500, 1000, 50), (1000, 1000, 100), (80, 1000, 8)],
)
def test_the_subtitle_states_the_share_of_todays_pool(remainder, pool, share):
    doc = document(remainder, grant=pool, pool=pool)
    status = qw.compose_qs(doc, *qw.QS_SIZES["large"])[1]
    assert int(SHARE.search(status).group(1)) == share


def test_a_single_byte_of_free_data_still_lights_the_tile():
    doc = document(1, grant=1, pool=1)
    assert qw.compose_qs(doc)[2] == "active"
    assert qw.compose_qs(document(0, grant=1, pool=1))[2] == "inactive"


def test_the_reset_time_on_the_tile_is_the_reset(clock):
    doc = document(int(1.68 * 1024**3), grant=GRANT)
    # The clock ends the subtitle at every rung that still has it, so this
    # also says nothing has been appended after it.
    assert qw.compose_qs(doc, *qw.QS_SIZES["medium"])[1].endswith("00:00")
    assert qw.compose_qs(doc, *qw.QS_SIZES["large"])[1].endswith("00:00")


@pytest.mark.parametrize("grant, spelling", [
    (int(1.68 * 1024**3), "+1.68 GiB"),
    (10 * 1024**3, "+10.0 GiB"),
    (763 * 1024**2, "+763 MiB"),
])
def test_the_top_up_is_stated_as_precisely_as_the_wide_tile_can_hold(grant, spelling):
    """Eight characters for the top-up, which is what leaves room for the
    clock beside it — a ten-gibibyte grant gives up a decimal for it."""
    doc = document(20 * 1024**3, grant=grant, pool=20 * 1024**3)
    assert spelling in qw.compose_qs(doc, *qw.QS_SIZES["large"])[1]


def test_the_wide_tile_is_told_the_pool_as_well():
    doc = document(4 * 1024**3, grant=GRANT, pool=9 * 1024**3)
    status = qw.compose_qs(doc, *qw.QS_SIZES["large"])[1]
    assert qw.size(9 * 1024**3) in status


def test_the_tile_with_no_reading_says_what_the_documentation_says_it_says():
    """``docs/quota-tile.md`` quotes these two: the tile reads
    ``quota ? / no reading`` when there is nothing behind it."""
    assert qw.QS_UNKNOWN[:2] == ("quota ?", "no reading")


def test_the_ruler_is_at_least_twice_the_budget_it_is_measuring():
    """The last mark still visible is the answer, so the ruler has to run well
    past the tile — one that only just overhangs cannot be read off."""
    whole = [len(line) for line in qw.qs_probe(999, 999).split("\n")]
    for label, status in ((0, 0), (5, 10), (10, 16), (12, 34), (20, 40)):
        lines = qw.qs_probe(label, status).split("\n")
        assert len(lines[0]) >= min(2 * label, whole[0])
        assert len(lines[1]) >= min(2 * status, whole[1])
