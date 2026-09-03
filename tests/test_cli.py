"""The two command lines, end to end — with the portal replaced at the seam.

``main`` is what the widget shortcut, the Tasker tile and the status line all
call, so what is pinned here is the exit code, which stream the answer lands
on, and that calibration never costs a request.
"""

from __future__ import annotations

import json
import os

import pytest
from conftest import GRANT, balance_doc, reading
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

import quota_widget as qw
import zwana_quota as zq


@pytest.fixture
def portal(monkeypatch):
    class Portal:
        def __init__(self):
            self.answer = reading(remainder=int(1.68 * 1024**3), grant=GRANT)
            self.error: Exception | None = None
            self.calls = 0

        def gather(self, previous=None):
            self.calls += 1
            if self.error is not None:
                raise self.error
            return self.answer

    portal = Portal()
    monkeypatch.setattr(qw, "gather", portal.gather)
    return portal


def run(argv, capsys):
    code = qw.main(argv)
    captured = capsys.readouterr()
    return code, captured.out, captured.err


# --------------------------------------------------------------------------- #
# Calibration costs nothing
# --------------------------------------------------------------------------- #


def test_the_probe_never_goes_to_the_portal(capsys, monkeypatch):
    monkeypatch.setattr(
        qw, "gather", lambda previous=None: pytest.fail("calibration must be free")
    )
    code, out, _ = run(["--probe"], capsys)
    assert code == 0
    assert out.splitlines()[0].startswith("01 ----+----1")


def test_the_probe_logs_what_the_launcher_handed_us(capsys):
    run(["--probe"], capsys)
    report = json.loads(qw.PROBE_LOG.read_text())
    assert set(report) >= {"argv", "isatty", "terminal_size", "ppid", "env"}


@pytest.mark.parametrize("page", sorted(qw.GLYPH_PAGES))
def test_every_glyph_page_numbers_its_rows_past_the_tile(capsys, page):
    code, out, _ = run(["--probe", page], capsys)
    lines = out.splitlines()
    assert code == 0
    numbers = [int(line[:2]) for line in lines]
    assert numbers == list(range(1, len(lines) + 1))
    assert len(lines) > len(qw.GLYPH_PAGES[page]) + 1
    # Numbered well past any tile the launcher will draw: the last number
    # still visible in the screenshot is the row count, and it is the only
    # way to learn the tile grew.
    assert len(lines) >= 24


def test_the_quick_settings_probe_never_goes_to_the_portal(capsys, monkeypatch):
    monkeypatch.setattr(
        qw, "gather", lambda previous=None: pytest.fail("calibration must be free")
    )
    code, out, _ = run(["--qs-probe", "--qs-size", "large"], capsys)
    assert code == 0
    assert len(out.splitlines()) == 4


# --------------------------------------------------------------------------- #
# The four faces
# --------------------------------------------------------------------------- #


def test_the_default_is_the_tile_face_placed_in_the_tile(capsys, clock, portal):
    code, out, _ = run([], capsys)
    lines = out.split("\n")
    assert code == 0
    assert lines[:qw.TILE_TOP] == [""] * qw.TILE_TOP
    face = [line for line in lines[qw.TILE_TOP:] if line]
    assert face
    assert all(len(line) <= qw.TILE for line in face)


def test_the_quick_settings_answer_is_exactly_four_lines(capsys, clock, portal):
    code, out, _ = run(["--qs"], capsys)
    assert code == 0
    assert out.count("\n") == 4
    assert out.splitlines() == list(qw.compose_qs(
        qw.derive(portal.answer, 0.0, True), *qw.QS_SIZES["medium"]
    ))


def test_a_measured_width_beats_a_named_size(capsys, clock, portal):
    _, wide, _ = run(["--qs", "--qs-size", "small", "--qs-width", "12", "34"], capsys)
    _, named, _ = run(["--qs", "--qs-size", "large"], capsys)
    assert wide == named


def test_json_is_the_derivation_verbatim(capsys, clock, portal):
    code, out, _ = run(["--json"], capsys)
    assert code == 0
    assert json.loads(out) == qw.derive(portal.answer, 0.0, True)


def test_the_line_face_is_one_line(capsys, clock, portal):
    code, out, _ = run(["--line"], capsys)
    assert code == 0
    assert out.count("\n") == 1


def test_the_full_face_draws_the_box(capsys, clock, portal):
    code, out, _ = run(["--full"], capsys)
    assert code == 0
    assert "╭" in out and "╰" in out


@pytest.mark.parametrize("width", [24, 35, 48])
def test_an_asked_for_width_is_the_width_drawn(capsys, clock, portal, width):
    _, tile, _ = run(["--width", str(width)], capsys)
    _, full, _ = run(["--full", "--width", str(width), "--frame", "corners"], capsys)
    assert all(len(line) <= width for line in tile.split("\n"))
    assert len(full.split("\n")[0]) == width


def test_the_top_and_margin_are_asked_for_in_rows_and_columns(capsys, clock, portal):
    _, none, _ = run(["--top", "0", "--margin", "0"], capsys)
    _, moved, _ = run(["--top", "3", "--margin", "2"], capsys)
    assert not none.startswith("\n")
    assert moved.startswith("\n\n\n")
    assert moved.split("\n")[3].startswith("  ")


def test_the_top_and_margin_are_the_tiles_and_not_the_boxs(capsys, clock, portal):
    _, tile, _ = run([], capsys)
    _, full, _ = run(["--full"], capsys)
    assert tile.startswith("\n" * qw.TILE_TOP)
    assert not full.startswith("\n")


# --------------------------------------------------------------------------- #
# Colour
# --------------------------------------------------------------------------- #


def test_a_pipe_gets_no_escape_codes(capsys, clock, portal):
    _, out, _ = run([], capsys)
    assert "\033[" not in out


def test_a_terminal_gets_colour_unless_it_is_refused(capsys, clock, portal, monkeypatch):
    monkeypatch.setattr(qw.sys.stdout, "isatty", lambda: True, raising=False)
    monkeypatch.delenv("TERM", raising=False)
    _, coloured, _ = run([], capsys)
    _, plain, _ = run(["--plain"], capsys)
    monkeypatch.setenv("TERM", "dumb")
    _, dumb, _ = run([], capsys)
    assert "\033[" in coloured
    assert "\033[" not in plain
    assert "\033[" not in dumb


# --------------------------------------------------------------------------- #
# When the portal is down
# --------------------------------------------------------------------------- #


def test_a_cached_reading_is_drawn_when_the_portal_is_down(capsys, clock, portal):
    qw.store(reading(remainder=5 * 1024**2, grant=GRANT, ts=clock.epoch - 7200))
    portal.error = zq.PortalError("no route")
    code, out, err = run(["--refresh"], capsys)
    assert code == 0
    assert "ago" in out
    assert err == ""


def test_with_no_reading_at_all_the_tile_says_so_and_fails(capsys, clock, portal):
    portal.error = zq.PortalError("no route")
    code, out, err = run(["--qs"], capsys)
    assert code == 1
    assert out.splitlines() == list(qw.QS_UNKNOWN)
    assert "no route" in err


def test_the_failure_goes_to_stderr_so_the_face_stays_readable(capsys, clock, portal):
    portal.error = zq.PortalError("no route")
    code, out, err = run([], capsys)
    assert code == 1
    assert out == ""
    assert "quota unavailable" in err


# --------------------------------------------------------------------------- #
# --refresh-only, what the detached refresher runs
# --------------------------------------------------------------------------- #


def test_a_background_refresh_updates_the_cache_and_says_nothing(
    capsys, clock, portal
):
    code, out, err = run(["--refresh-only"], capsys)
    assert (code, out, err) == (0, "", "")
    assert qw.stale() == portal.answer


def test_a_background_refresh_that_fails_leaves_the_cache_alone(capsys, clock, portal):
    old = reading(remainder=7, ts=clock.epoch - 7200)
    qw.store(old)
    portal.error = zq.PortalError("no route")
    code, out, _ = run(["--refresh-only"], capsys)
    assert code == 1
    assert out == ""
    assert qw.stale() == old


def test_a_background_refresh_hands_the_old_reading_forward(capsys, clock, portal):
    seen: list[dict | None] = []
    qw.store(reading(remainder=7, ts=clock.epoch - 7200))
    portal.gather = lambda previous=None: (seen.append(previous), portal.answer)[1]
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(qw, "gather", portal.gather)
        run(["--refresh-only"], capsys)
    assert seen and seen[0]["remainder"] == 7


# --------------------------------------------------------------------------- #
# The argument surface
# --------------------------------------------------------------------------- #


def test_the_defaults_are_the_documented_ones():
    args = qw.parse_args([])
    assert args.max_age == qw.DEFAULT_MAX_AGE
    assert args.qs_size == "medium"
    assert args.qs_width is None
    assert args.frame == "corners"
    assert (args.width, args.margin, args.top) == (None, None, None)


@pytest.mark.parametrize("argv", [["--qs-size", "enormous"], ["--frame", "double"],
                                  ["--probe", "emoji"], ["--qs-width", "1"]])
def test_an_unknown_option_is_refused_rather_than_guessed(argv):
    with pytest.raises(SystemExit):
        qw.parse_args(argv)


@settings(suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(max_age=st.floats(0, 10**5, allow_nan=False))
def test_max_age_reaches_the_cache_policy(clock, portal, spawned, max_age):
    """A reading younger than ``--max-age`` is simply used; an older one is
    still drawn, with a refresh started behind it."""
    spawned.clear()
    qw.LOCK.unlink(missing_ok=True)
    qw.CACHE.unlink(missing_ok=True)
    qw.store(reading(remainder=5, ts=clock.epoch - 100))
    qw.main(["--json", "--max-age", str(max_age)])
    assert portal.calls == 0
    assert (spawned == []) is (max_age >= 100)


# --------------------------------------------------------------------------- #
# The portal client's own command line
# --------------------------------------------------------------------------- #


@pytest.fixture
def client(monkeypatch):
    class Client:
        def __init__(self):
            self.docs = {
                "Balance/GetForCurrentUser": balance_doc(2.5),
                "UserProvider/GetStatus": {"Connected": True},
                "Allocation/GetHistoryForCurrentUser": [],
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

    client = Client()
    monkeypatch.setattr(zq, "load_session", lambda: client.session)
    monkeypatch.setattr(zq, "log_in", lambda env: setattr(
        client, "logins", client.logins + 1))
    monkeypatch.setattr(zq, "fetch", client.fetch)
    return client


def test_the_client_summarises_what_is_left(capsys, client):
    assert zq.main([]) == 0
    out = capsys.readouterr().out
    assert "2.5 credits" in out
    assert zq.human_bytes(2.5 * zq.BYTES_PER_CREDIT) in out


def test_the_client_json_states_the_bytes_and_the_rate(capsys, client):
    assert zq.main(["--json"]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["bytes_per_credit"] == zq.BYTES_PER_CREDIT
    assert payload["remaining_bytes"] == int(2.5 * zq.BYTES_PER_CREDIT)
    assert payload["status"] == {"Connected": True}


def test_the_status_call_is_only_made_when_it_will_be_shown(capsys, client):
    zq.main([])
    assert "UserProvider/GetStatus" not in client.asked
    zq.main(["--verbose"])
    assert "UserProvider/GetStatus" in client.asked


def test_login_forces_a_fresh_session(capsys, client):
    client.session = True
    zq.main([])
    assert client.logins == 0
    zq.main(["--login"])
    assert client.logins == 1


def test_a_portal_failure_is_an_error_on_stderr_and_a_non_zero_exit(capsys, client):
    client.docs["Balance/GetForCurrentUser"] = zq.PortalError("cannot reach portal")
    assert zq.main([]) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "cannot reach portal" in captured.err


def test_a_payload_with_no_balance_is_printed_rather_than_hidden(capsys):
    zq.summarise({"Unexpected": 1}, None, verbose=False, per_credit=1)
    out = capsys.readouterr().out
    assert "Unexpected" in out


def test_a_session_state_is_reported_when_the_portal_states_one(capsys):
    zq.summarise(balance_doc(1.0, online=False), None, False, zq.BYTES_PER_CREDIT)
    assert "offline" in capsys.readouterr().out


# --------------------------------------------------------------------------- #
# The edges: what main prints, exactly, and what the probe log is for
# --------------------------------------------------------------------------- #


def test_the_tile_printed_is_the_tile_composed(capsys, clock, portal):
    """No blank line, no stray character and no second margin between the
    composition and the terminal — the widget draws whatever comes back."""
    _, out, _ = run([], capsys)
    doc = qw.derive(portal.answer, 0.0, True)
    expected = qw.render_tile(doc, qw.Paint(False), qw.TILE, qw.TILE_MARGIN)
    assert out == "\n" * qw.TILE_TOP + expected + "\n"


def test_the_full_box_is_drawn_flush_when_no_margin_is_asked_for(
    capsys, clock, portal
):
    _, out, _ = run(["--full", "--margin", "0"], capsys)
    assert not out.startswith(" ")
    assert out.split("\n")[0].startswith("╭")


def test_the_probe_log_says_what_the_launcher_gave_us(capsys):
    """It exists to answer "does the widget get a pty, and any geometry at
    all" over the shell, after the screenshot has been taken."""
    run(["--probe"], capsys)
    report = json.loads(qw.PROBE_LOG.read_text())
    assert report["ppid"] == os.getppid()
    assert isinstance(report["parent_cmdline"], str)
    assert sorted(report["isatty"]) == ["stdin", "stdout"]
    assert all(isinstance(flag, bool) for flag in report["isatty"].values())
    assert len(report["terminal_size"]) == 2
    assert report["cwd"] == os.getcwd()
    assert report["env"] and "PATH" in report["env"]


def test_the_ruler_pages_are_ten_characters_of_one_glyph_each(capsys):
    for page, tests in qw.GLYPH_PAGES.items():
        code, out, _ = run(["--probe", page], capsys)
        lines = out.splitlines()
        assert code == 0
        # Two more rows than the last test: the ruler wraps, and the numbering
        # runs past the tile so the last one visible is the row count.
        assert len(lines) == 24
        for line, (name, glyph) in zip(lines[1:], tests, strict=False):
            run_of_glyphs, _, shown = line[3:].partition(" ")
            assert run_of_glyphs == glyph * 10
            assert shown == name


def test_the_command_line_reads_the_real_argv_when_it_is_given_none(monkeypatch):
    monkeypatch.setattr(qw.sys, "argv", ["quota_widget.py", "--qs", "--plain"])
    args = qw.parse_args()
    assert (args.qs, args.plain) == (True, True)
