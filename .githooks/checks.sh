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
# Exit 5 is pytest for "no tests collected", and it is a failure here. It was
# passed over while the suite was being rebuilt (the old quota_widget
# self-test came out on 2026-09-02); the suite landed, so an empty collection
# now means the tests did not import — which is exactly the state a push must
# not go out in.
set -u

cd "$(git rev-parse --show-toplevel)" || exit 1

if [ -d .venv ] && command -v uv >/dev/null 2>&1; then
    uv run pytest -q
else
    python3 -m pytest -q
fi
rc=$?

if [ "$rc" -eq 5 ]; then
    echo "checks: pytest collected nothing — the suite under tests/ did not run." >&2
    exit 1
fi
exit $rc
