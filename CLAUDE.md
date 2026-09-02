# zwana-quota — working notes for Claude

The phone's metered-data readout: `zwana_quota.py` (the portal client for
`ic.zwana.io`), `quota_widget.py` (the widget face and the library), and
`tasker/zwana-tile` (the Quick Settings tile). Split out of the `or3`
monorepo's `termux/` on 2026-08-28. The `dlq` repo's nightly runner imports
`quota_widget` from this checkout — this repo depends on nothing.

Decisions that travel with this code:

- **`zwana-tile` prints four lines Tasker addresses by position**, so their
  order is the interface and a test must pin it. It prints four lines and
  exits 0 whatever happens, because a tile nobody watches fail must carry its
  failure on its face rather than abort the task that draws it; its state is
  never Android's `UNAVAILABLE`, which would grey out the tap that is how it
  recovers. `docs/quota-tile.md`.
- **The face must fit 35 x 5 at every magnitude** (`TILE` in
  `quota_widget.py`): overflowing is not an error, it is a widget with a line
  clipped off. The figure and its small print share a row, so a wide figure
  crowds the print sideways rather than off the bottom; the reset time is
  what has to survive that, being the one thing on the face that cannot be
  inferred from the rest. A stale or offline reading must be checked to be
  *drawn*, not merely to fit. Never let a line wrap.
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

`make test` (pytest) = `make check` (`.githooks/checks.sh`, the one copy; the
pre-push hook runs it). Offline: the portal is never reached. This repo needs
no sibling checkout — it is the one the others reach down into.

**There are no tests right now.** `quota_widget.py --self-test` was deleted
outright on 2026-09-02 — the function, the flag, and the pytest shim over it —
so that it can be rebuilt as a real pytest suite under `tests/`. Until that
lands `checks.sh` reads pytest's exit 5 as "no tests yet" and passes, which is
what keeps the interim commits pushable; the suite tightens it.

Two names went with it, because nothing but the check ever used either:
`_fake` (a derived document for a given remainder, with no portal involved —
dlq's screens borrowed it through its own fixture, which is gone too) and
`TILE_ROWS`. The suite will want both back.

What the suite has to cover: the face fitting 35 x 5 at every magnitude the
figure can take — the unit thresholds, the values that gain a digit, and the
ones `face_value` drops a decimal for — the Quick Settings label and status
budgets at every width, a stale or offline reading being *drawn* and not
merely fitting, and the four-line order `compose_qs` is the one spelling of.
The tile script itself deliberately has no checks of its own.
