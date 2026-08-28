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

## The portal API

Findings that cost real time to work out (2026-08-01, on the unminified
AngularJS SPA the portal is):

1. `POST api/account/token` looks like an OAuth2 password grant but wants a
   **JSON** body, not form encoding.
2. Its response `token_type` is literally `"password"` (it echoes the grant
   type), and the `access_token` it hands back **does not authorise
   anything**. Auth is really the `.AspNetCore.Identity.Application`
   **cookie** from `Set-Cookie`; sending the token as an Authorization header
   earns a 302 back to the login page.
3. `Balance` is denominated in **credits, not bytes**. A `-0.1` credit entry
   corresponds to `Allocation: 41943040` (40 MiB), and the provider's
   `UnitCost` of `2.38418579102e-09` credits/byte confirms it:
   **one credit = 400 MiB exactly.**

Useful endpoints: `Balance/GetForCurrentUser`, `UserProvider/GetStatus`,
`Allocation/GetHistoryForCurrentUser`, `account/getcurrent`.

The daily grant (763 MiB) lands at **00:02:19 UTC** and leftover quota does
not roll over — which is the whole reason the `dlq` repo exists.
