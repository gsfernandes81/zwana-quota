# zwana-quota — working notes for Claude

The phone's metered-data readout: `zwana_quota.py` (the portal client for
`ic.zwana.io`), `quota_widget.py` (the widget face and the library), and
`tasker/zwana-tile` (the Quick Settings tile). Split out of the `or3`
monorepo's `termux/` on 2026-08-28. The `dlq` repo's nightly runner imports
`quota_widget` from this checkout — this repo depends on nothing.

Decisions that travel with this code:

- **`zwana-tile` prints four lines Tasker addresses by position**, so their
  order is the interface and the self-test pins it. It prints four lines and
  exits 0 whatever happens, because a tile nobody watches fail must carry its
  failure on its face rather than abort the task that draws it; its state is
  never Android's `UNAVAILABLE`, which would grey out the tap that is how it
  recovers. `docs/quota-tile.md`.
- **The face must fit 35 x 5 at every magnitude** (`TILE` in
  `quota_widget.py`): overflowing is not an error, it is a widget with a line
  clipped off. The figure and its small print share a row, so a wide figure
  crowds the print sideways rather than off the bottom; the reset time is
  what the checks make survive that, being the one thing on the face that
  cannot be inferred from the rest. A stale or offline reading is checked to
  be *drawn*, not merely to fit. Never let a line wrap.
- **Credentials come from `.env` at the repo root** (`DEFAULT_ENV`), keys
  lowercase ON PURPOSE — `zwana_username` is an interface shared with files
  that already exist on the phone; capitalising it would silently stop
  finding them (ruff SIM112 is off for this reason). The session cookie is
  cached in `~/.cache/zwana/` so a repeat check costs one request instead of
  two.
- The nightly grant is spent first and expires at the reset; paid allocations
  do not — `free.left_bytes` can only over-state, which is the direction the
  dlq runner's guards need (see the accuracy block in `quota_widget.py`).

## Checks

`make test` (pytest) = `make check` (`.githooks/checks.sh`; the pre-push hook
runs it). One suite: `quota_widget.py --self-test`, offline, which carries
the tile's string budgets and four-line contract — the tile script itself
deliberately has no checks of its own.
