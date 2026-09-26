"""The golden vectors still say what the live pipeline says.

``vectors/quota.json`` is the contract the Android port is tested against: the
Kotlin suite runs its own ``gather`` and ``derive`` over the same inputs and
must land on the same numbers. That is only worth anything while the file is
the truth about *this* code, so a change to the derivation fails here until
``make vectors`` has rewritten it — and the change then shows in the diff of a
file the Kotlin side reads, which is the prompt to carry it across.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "vectors"))

import make_vectors  # noqa: E402


def test_the_checked_in_vectors_are_what_the_code_produces():
    written = json.loads(make_vectors.OUT.read_text())
    live = json.loads(make_vectors.render(make_vectors.build()))
    assert written == live, "vectors/quota.json is stale: run `make vectors`"


def test_the_vectors_cover_both_halves_and_the_failures():
    vectors = make_vectors.build()
    assert len(vectors["gather"]) >= 10
    assert len(vectors["derive"]) >= 40
    assert any(case["expect"] == {"error": True} for case in vectors["gather"])
    assert any(case["history"] is None for case in vectors["gather"])
    # Integers past 2^31 and 2^32 are where a port with the wrong width breaks.
    assert any(c["raw"]["remainder"] > 2**32 for c in vectors["derive"])


def test_building_the_vectors_leaves_the_clock_and_the_portal_alone():
    """The frozen clock and the stubbed portal are put back afterwards."""
    import quota_widget as qw
    import zwana_quota as zq

    before = qw.dt, qw.time, zq.fetch, zq.load_session
    make_vectors.build()
    assert (qw.dt, qw.time, zq.fetch, zq.load_session) == before
