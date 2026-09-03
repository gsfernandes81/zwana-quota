"""The cache, the staleness policy and the detached refresh.

The rule under all of it: a reading that came off the disk is never handed
back as live, whatever its age, because ``live`` is what the face and the tile
use to decide whether to say the figure overstates what is left.
"""

from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path

import pytest
from conftest import reading
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

import quota_widget as qw
import zwana_quota as zq


@pytest.fixture
def portal(monkeypatch):
    """Count the trips to the portal ``current`` makes, and what it passed on."""

    class Portal:
        def __init__(self):
            self.calls: list[dict | None] = []
            self.answer = reading(remainder=1234)
            self.error: Exception | None = None

        def gather(self, previous=None):
            self.calls.append(previous)
            if self.error is not None:
                raise self.error
            return self.answer

    portal = Portal()
    monkeypatch.setattr(qw, "gather", portal.gather)
    return portal


# --------------------------------------------------------------------------- #
# store / cached / stale
# --------------------------------------------------------------------------- #


def test_a_stored_reading_comes_back_whole_and_private(clock):
    data = reading(remainder=99, ts=clock.epoch)
    qw.store(data)
    assert qw.cached(60) == data
    assert json.loads(qw.CACHE.read_text()) == data
    assert stat.S_IMODE(qw.CACHE.stat().st_mode) == 0o600
    assert stat.S_IMODE(qw.CACHE.parent.stat().st_mode) == 0o700


def test_storing_replaces_rather_than_appends(clock):
    qw.store(reading(remainder=1, ts=clock.epoch))
    qw.store(reading(remainder=2, ts=clock.epoch))
    assert qw.cached(60)["remainder"] == 2


def test_the_cache_policy_figures_are_the_ones_the_tile_was_wired_to():
    """``tasker/zwana-tile`` passes ``--max-age 45`` and is written against
    these; the lock's timeout is how long a killed refresher can freeze the
    figure before another is allowed to try."""
    assert qw.DEFAULT_MAX_AGE == 45
    assert qw.LOCK_TIMEOUT == 120


def test_a_reading_exactly_max_age_old_is_still_fresh(clock):
    qw.store(reading(ts=clock.epoch - 45))
    assert qw.cached(45) is not None
    assert qw.cached(44.9) is None


def test_an_older_reading_is_not_fresh_but_is_still_the_last_one(clock):
    qw.store(reading(remainder=7, ts=clock.epoch - 3600))
    assert qw.cached(45) is None
    assert qw.stale()["remainder"] == 7


def test_no_cache_at_all_is_no_reading():
    assert qw.cached(45) is None
    assert qw.stale() is None


@pytest.mark.parametrize(
    "body", ["", "  ", "{", "null", "[1, 2]", '"text"', '{"remainder": 5}'],
    ids=["empty", "blank", "truncated", "null", "list", "string", "no timestamp"],
)
def test_an_unreadable_cache_is_no_reading_rather_than_a_crash(body):
    qw.CACHE.write_text(body)
    assert qw.cached(45) is None
    assert qw.stale() is None


def test_a_cache_that_cannot_be_opened_is_no_reading():
    qw.CACHE.mkdir(parents=True)
    assert qw.cached(45) is None


# --------------------------------------------------------------------------- #
# current(): what is live and what is not
# --------------------------------------------------------------------------- #


def test_a_fresh_cache_answers_without_the_portal(clock, portal, spawned):
    qw.store(reading(remainder=5, ts=clock.epoch))
    data, live = qw.current(45)
    assert data["remainder"] == 5
    assert live is False
    assert portal.calls == []
    assert spawned == []


def test_a_stale_cache_answers_at_once_and_refreshes_behind_it(clock, portal, spawned):
    qw.store(reading(remainder=5, ts=clock.epoch - 600))
    data, live = qw.current(45)
    assert data["remainder"] == 5
    assert live is False
    assert portal.calls == []
    assert len(spawned) == 1


def test_with_no_reading_at_all_it_blocks_to_get_one(clock, portal, spawned):
    data, live = qw.current(45)
    assert live is True
    assert data == portal.answer
    assert portal.calls == [None]
    assert qw.stale() == portal.answer
    assert spawned == []


def test_force_goes_to_the_portal_even_over_a_fresh_reading(clock, portal):
    qw.store(reading(remainder=5, ts=clock.epoch))
    data, live = qw.current(45, force=True)
    assert live is True
    assert data["remainder"] == 1234
    assert qw.stale()["remainder"] == 1234


def test_the_previous_reading_is_handed_to_the_portal_read(clock, portal):
    """``gather`` needs it: it is what keeps the grant and the pool across a
    history the portal declined to serve."""
    old = reading(remainder=5, ts=clock.epoch - 600)
    qw.store(old)
    qw.current(45, force=True)
    assert portal.calls == [old]


def test_a_portal_failure_with_nothing_cached_is_raised(clock, portal):
    portal.error = zq.PortalError("down")
    with pytest.raises(zq.PortalError):
        qw.current(45, force=True)


@settings(suppress_health_check=[HealthCheck.function_scoped_fixture])
@given(
    age=st.floats(0, 10**5),
    max_age=st.floats(0, 10**5),
    force=st.booleans(),
    have_cache=st.booleans(),
)
def test_live_is_true_only_when_the_portal_was_actually_asked(
    clock, portal, age, max_age, force, have_cache
):
    portal.calls.clear()
    qw.CACHE.unlink(missing_ok=True)
    if have_cache:
        qw.store(reading(remainder=5, ts=clock.epoch - age))
    data, live = qw.current(max_age, force=force)
    assert live is (portal.calls != [])
    if not live:
        # Whatever came back off the disk is the reading that was put there.
        assert data["ts"] == clock.epoch - age


# --------------------------------------------------------------------------- #
# spawn_refresh(): one at a time, and never wedged
# --------------------------------------------------------------------------- #


def test_a_refresh_is_detached_and_shares_no_pipes(clock, spawned):
    qw.spawn_refresh()
    (argv, kwargs), = spawned
    assert argv[0] == "sh"
    script = argv[-1]
    assert "--refresh-only" in script
    assert str(qw.LOCK) in script
    assert kwargs["start_new_session"] is True
    assert kwargs["stdin"] is subprocess.DEVNULL
    assert kwargs["stdout"] is subprocess.DEVNULL
    assert kwargs["stderr"] is subprocess.DEVNULL


def test_a_second_caller_does_not_start_a_second_refresh(clock, spawned):
    qw.spawn_refresh()
    qw.spawn_refresh()
    assert len(spawned) == 1


def test_a_refresher_that_died_does_not_freeze_the_figure_forever(clock, spawned):
    qw.spawn_refresh()
    os.utime(qw.LOCK, (0, clock.epoch - qw.LOCK_TIMEOUT - 1))
    qw.spawn_refresh()
    assert len(spawned) == 2


def test_a_lock_still_within_its_time_is_respected(clock, spawned):
    qw.spawn_refresh()
    os.utime(qw.LOCK, (0, clock.epoch - qw.LOCK_TIMEOUT + 1))
    qw.spawn_refresh()
    assert len(spawned) == 1


def test_a_refresh_that_cannot_be_started_leaves_no_lock_behind(clock, monkeypatch):
    def refuse(*_a, **_k):
        raise OSError("no fork")

    monkeypatch.setattr(qw.subprocess, "Popen", refuse)
    qw.spawn_refresh()
    assert not qw.LOCK.exists()


def test_the_cache_directory_is_made_before_the_lock(clock, spawned):
    shutil.rmtree(qw.CACHE.parent.parent)
    qw.spawn_refresh()
    assert qw.LOCK.exists()
    assert len(spawned) == 1


# --------------------------------------------------------------------------- #
# The edges: the lock's own timing and permissions, a cache with no directory
# --------------------------------------------------------------------------- #


def test_a_reading_can_be_stored_before_the_cache_directory_exists(clock):
    shutil.rmtree(qw.CACHE.parent.parent)
    qw.store(reading(remainder=3, ts=clock.epoch))
    assert qw.cached(60)["remainder"] == 3


def test_a_lock_exactly_at_its_timeout_is_still_somebody_elses(clock, spawned):
    qw.spawn_refresh()
    os.utime(qw.LOCK, (0, clock.epoch - qw.LOCK_TIMEOUT))
    qw.spawn_refresh()
    assert len(spawned) == 1


def test_the_lock_is_private_like_everything_else_in_the_cache(clock, spawned):
    qw.spawn_refresh()
    assert stat.S_IMODE(qw.LOCK.stat().st_mode) == 0o600


def test_the_refresh_runs_this_module_with_this_interpreter(clock, spawned):
    """It is spelled out because the detached process has no shell to inherit
    a `python` or a working directory from."""
    qw.spawn_refresh()
    script = spawned[0][0][-1]
    assert spawned[0][0][:2] == ["sh", "-c"]
    assert sys.executable in script
    assert str(Path(qw.__file__).resolve()) in script
