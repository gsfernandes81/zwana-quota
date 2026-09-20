# The quota on the Windows 11 widgets board — plan

The same reading the phone shows, on the Windows 11 widgets board: what is
left of today's allowance and how long until it resets.

**Status: plan only. Nothing is built.** The four decisions below were
settled by the repo owner on 2026-09-19 and are recorded as answers, not
recommendations; one question remains open (§5, the CI workflow). Read
`../README.md`'s *The portal API* section first — it is the contract this plan
ports, and it stays the single source of truth.

## What the port actually owes the portal

Read off `zwana_quota.py` and `quota_widget.py` rather than remembered:

| step | detail |
|---|---|
| log in | `POST api/account/token`, **JSON** body `{grant_type, username, password}` |
| keep | the `.AspNetCore.Identity.Application` cookie from `Set-Cookie`. The `access_token` authorises nothing; sending it as a bearer header earns a 302 |
| never follow redirects | the API answers an unauthenticated request with **302 to the login page**, not 401. `HttpClientHandler.AllowAutoRedirect = false`, and treat 3xx as "session gone, log in again" — `NoRedirect` in `zwana_quota.py` exists for exactly this |
| the figure | `GET api/UserProvider/GetActive` → `Remainder` (bytes left, exact) and `Allocated` |
| the reserve | `GET api/Balance/GetForCurrentUser` → `Balance` (credits), `Online`, `CronProfileName` |
| the grant and the day's draws | `GET api/Allocation/GetHistoryForCurrentUser`; free entries are `CreditHistory.TopUpMethod == 4` |
| credits → bytes | 1 credit = 419 430 400 B (400 MiB), but prefer the history's own `UnitCost` where a byte-type provider states it, as `gather()` does |

## The finding that shapes the plan

**The headline number needs no derivation.** Both Android surfaces lead with
`today.remainder_bytes` (`quota_widget.py`, `compose_tile` and `compose_qs`),
which is the portal's own `Remainder` verbatim. So "remaining allowance plus
time to reset" — the whole definition of done — is a cookie login, one GET,
and a local clock. It duplicates no arithmetic at all.

**The derived figures are the expensive half, and they are stateful.** The
share of today's pool, tonight's top-up and the free/paid split come out of
`gather` → `day_pool` → `derive`, and `day_pool` is a *high-water mark carried
between runs in the cache* (`pool`, `pool_first_ts`). Porting those is not a
second copy of the auth dance; it is a second copy of the only part of this
repo that has invariants — the upper/lower bound property the `dlq` runner's
guards stand on, which `CLAUDE.md` names as a check not to weaken.

And it has a consequence no comment can paper over: **two caches can derive
two different splits from identical portal data.** The pool is seeded by when
that cache first looked today, so a Windows cache that first looks at 09:00
can infer a different `carry_in` from the phone's, which first looked at
00:03. `Remainder` and the reset always agree; the percentage and the free/paid
split may not. Anything the widget draws from the derivation therefore either
accepts that drift or is not drawn.

Hence the work is **tiered**. Both tiers are in scope (§2), but they land in
that order, because tier 0 is what has to be on the board before the harder
half is worth writing:

- **Tier 0** — remainder, the countdown, stale/offline honesty. No derivation,
  no cross-activation state beyond a cached reading. This is the definition
  of done, and it ships first.
- **Tier 1** — share of pool, tonight's top-up, free vs paid. Ports `day_pool`
  and `derive`, and owes the drift above an answer.

Tier 1 ships with **golden vectors**: a `make vectors` target writes the
numbers `derive` produces for a set of raw readings to
`windows/tests/vectors.json`, a Python test asserts the checked-in file still
matches the live `derive`, and the C# suite reads the same file. That pins the
*numbers*, which is the interface, and not the wording, which `CLAUDE.md`
deliberately leaves free.

**The drift has a direction, and it is the safe one.** A Windows cache that
first looks late in the day sees a lower pool high-water mark, so it infers a
smaller `carry_in`, so its `free_left` comes out *larger*. That is the same
direction `derive`'s own accuracy block already documents — `free.left_bytes`
is an upper bound and never a floor — only looser. So the Windows widget can
over-state what is free, exactly as the phone can, and never under-state it;
nothing reads the Windows cache but the Windows widget, and the `dlq` runner's
guards never see it. What the face owes in return is the same honesty the
phone's does: the headline is `Remainder`, which always agrees with the phone,
and the derived share is small print that says when it is an estimate.

## Decisions

### 1. Same repo, under `windows/` — **settled**

One portal contract, one README section, one commit when the portal moves.
The toolchains genuinely share nothing, so `windows/` is inert on a phone: no
Python import reaches into it, `make test` never sees it, and the pre-push
hook does not change (§5).

### 2. Reimplement the client in C#, both tiers — **settled**

Shelling out to Python (b) needs a Python on the Windows box an MSIX cannot
assume; a cache file written by something else (c) is (a) plus a scheduled
fetcher. (a) it is, and both tiers: the widget gets the whole face, not just the
figure. Tier 0 lands first and is the gate on tier 0's cadence and cache being
right before the derivation is copied at all.

The constants — 400 MiB/credit, the 763 MiB grant, the reset — get defined
once in C# with a comment naming the README section, and any change to them
is a change to both in the same commit.

### 3. Credentials: Windows Credential Locker, entered in a settings window — **settled**

`.env` is wrong on Windows. `PasswordVault` is per-user, packaged-app-native
and needs no key management; a DPAPI-protected file under
`LocalApplicationData` is the fallback if the packaging shape rules it out.
Either way the password never lands in the repo or in plaintext on disk.

They are entered in **a minimal settings window in the same package** (two
fields and Save), because **the widget cannot host a text input** and the COM
server has no UI. The widget's signed-out card raises it through an
`Action.Execute` the provider handles by launching the window — not a
protocol handler, since what a widget card is allowed to invoke is narrower
than full Adaptive Cards and is worth not depending on. A password change is
then the same window again, with nothing to remember.

### 4. Refresh: match the phone, which does not poll at all

The portal is the vessel's own, on this machine as on the phone: reaching
`ic.zwana.io` costs no quota, so the cadence is not about data cost. It is
still the phone's cadence, because a widget that polls a captive portal in the
background is spending the host's battery and goodwill for a figure nobody is
looking at. **The Android surfaces have no background polling.** The
cache max age is 45 s (`DEFAULT_MAX_AGE`, and `CACHE_MAX_AGE` in
`tasker/zwana-tile`) and a fetch happens only when someone taps the tile or
opens the widget. So:

- fetch only between `Activate` and `Deactivate`, i.e. only while the board is
  open and the widget is on screen;
- render the cached reading *first*, then fetch, then update only if the
  figure changed — the docs warn the Activate/Deactivate window can be very
  short, so the fetch is async, cancellable, and never blocks the first draw;
- hold the last reading and its timestamp in the widget's **custom state**, so
  it survives the COM server being torn down between activations
  (`GetWidgetInfos()` on start is how it comes back);
- compute the countdown locally — it needs no network ever;
- age the reading exactly as the phone does: a reading the widget cannot stand
  behind says so on its face rather than being drawn as if current.

One discrepancy to settle rather than inherit by accident: the README says the
grant lands at **00:02:19 UTC**, but `next_reset()` targets **00:00:00 UTC**
(`RESET_HOUR_UTC = 0`), so the phone's countdown runs 2m 19s early. The
Windows side should match the phone until both change together, or the two
surfaces will disagree by two minutes every night.

### 5. CI: `windows-latest` builds the package — **settled, and load-bearing**

No MSBuild in `.githooks/checks.sh` — it runs on Termux, where it cannot build
anything Windows, and a push here is a deploy that has to stay quick. The
Windows half gets its own workflow instead:
`.github/workflows/windows-msix.yml`, `windows-latest`, scoped to
`paths: ['windows/**']`, free on a public repository.

It turned out to be more than a checks question. **The target machine has no
build tooling and cannot download any over its link**, so this runner is not
a second opinion on a build that also happens locally — it is the only
compiler the Windows half has, and every install starts as an artifact
downloaded from a run. That moves it from phase 4 to the beginning, and it
sets two constraints on everything after it:

- the package bundles the .NET and Windows App SDK runtimes by default, since
  asking that machine to fetch a runtime is the same problem again; and
- signing happens in CI. A self-signed certificate is minted per run, or a
  pinned one is used if `MSIX_PFX_BASE64` / `MSIX_PFX_PASSWORD` are set as
  repository secrets. **The repository is public**: the `.pfx` and its
  password are secrets and never files. `windows/README.md` has both paths,
  and the manifest's `Publisher` is rewritten to match whichever certificate
  signs the build, because a package whose publisher and signer disagree will
  not install.

Tier 1 gives the workflow something worth running beyond the package itself:
the golden vectors are only a contract if something checks them on both
sides.

## Step 0, before any zwana code: does the platform still work here?

Microsoft's widget-provider docs are live and carry no deprecation notice, but
the platform had no Build 2026 session, and "sideloaded widget never appears
in the picker" is a recurring report — including on 26200.x, the target build
family. So the first thing installed draws nothing:

1. Take the `zwana-quota-widget-msix` artifact from a run of the workflow —
   the phase 1 skeleton, which pins and draws a placeholder and touches no
   portal.
2. `install.ps1` from an elevated PowerShell (`windows/README.md`).
3. Confirm it appears in the picker, pins to the board, and survives a reboot.

It is our own skeleton rather than the Microsoft sample because the machine
cannot build either one, so both arrive the same way — and this one is the
thing we actually want pinned. It answers the same question: does a
sideloaded provider show up here at all.

Green: proceed to phase 2. Red: stop and re-decide the surface before writing
a line of quota code — `windows/README.md` has the order to check things in,
and that is a cheap afternoon against a week spent on a board that will not
show it.

## Plan of work

| phase | what | gate |
|---|---|---|
| 0 | the CI build path: `windows-latest`, MakeAppx, signing, an installable artifact | a run produces a `.msix` that installs |
| 1 | `windows/` skeleton: packaged C# app, COM server, manifest, a widget that draws a constant | it pins and survives a reboot |
| 2 | the portal client (login, cookie, `GetActive`), credentials in the Credential Locker, first-run path per §3 | tier 0 on the board, real figure |
| 3 | cache in custom state, Activate/Deactivate cadence, stale/offline face | survives sign-out/in |
| 4 | README: build, signing, sideload, where credentials live | reproducible after a reinstall |
| 5 | tier 1: `day_pool` and `derive` ported, golden vectors both sides read | Python and C# agree on the vectors |

## Visual

Not the terminal face. The 35 x 5 budget and the tile string budgets constrain
the Android surfaces and nothing here. The card follows the board's own theme
rather than hardcoding Catppuccin, unless the host turns out to allow colours
cleanly — which phase 1 will show.

## Out of scope

The Android surfaces, `quota_widget.py`'s face, the Tasker tile, the `dlq`
repo, and Store publishing. Sideload only.
