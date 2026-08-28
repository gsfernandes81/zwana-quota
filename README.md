# zwana-quota — the metered-data readout

How much of the day's data allowance is left, read from the crew portal at
`ic.zwana.io` and drawn three ways on the phone:

- **`quota_widget.py`** — the home-screen widget face (via Termux:Widget
  shortcuts in `~/.shortcuts/`), and the library the other two build on.
- **`tasker/zwana-tile`** — the same reading on an Android Quick Settings
  tile, through the Termux:Tasker plugin. `tasker/install.sh` links it in;
  `docs/quota-tile.md` is the guide.
- **`zwana_quota.py`** — the portal client itself: login, balance, reset
  time. Credentials live in `.env` at this repo's root (`~/zwana-quota/.env`,
  mode 600, gitignored) as `zwana_username` / `zwana_password`; the session
  cookie is cached under `~/.cache/zwana/`.

The [`dlq`](../dlq) overnight download queue reads every guard figure through
`quota_widget`, resolving this repo as a sibling checkout (`$ZWANA_HOME`, a
clone beside it, or `~/zwana-quota`) — this repo depends on nothing and is
the one the others reach down into.

Split out of the `or3` monorepo's `termux/` on 2026-08-28. If migrating: move
the old credentials with `mv ~/or3/.env ~/zwana-quota/.env`, and re-point the
`~/.shortcuts/quota*` scripts and the `~/.termux/tasker/` symlink
(`tasker/install.sh` redoes the latter).

## Checks

`make test` (pytest) or `make check` — `quota_widget.py --self-test`, which
also pins the string budgets and the four-line contract the Tasker tile
depends on. Offline: the portal is never reached.
