#!/bin/bash
# The checks: the pytest suite, one runner, one copy.
#
#     .githooks/checks.sh        run everything
#     make check                 the same thing
#
# The pre-push hook runs this and refuses the push on a failure — a push is a
# deploy here: the phone pulls it and the nightly runner runs what landed.
#
# Offline: no network, no portal. This repo needs no sibling checkout —
# it is the one the others reach down into.
#
# The old quota_widget self-test was removed on 2026-09-02 to be rebuilt as a
# pytest suite. Until that suite lands, tests/ is empty and pytest exits 5
# ("no tests collected") — which is reported here as "no tests yet" and passed,
# so the interim commits are pushable. The suite tightens this.
set -u

cd "$(git rev-parse --show-toplevel)" || exit 1

if [ -d .venv ] && command -v uv >/dev/null 2>&1; then
    uv run pytest -q
else
    python3 -m pytest -q
fi
rc=$?

if [ "$rc" -eq 5 ]; then
    echo "checks: no tests yet — nothing was collected."
    exit 0
fi
exit $rc
