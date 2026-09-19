# The quota on the Windows 11 widgets board — plan

The same reading the phone shows, on the Windows 11 widgets board: what is
left of today's allowance and how long until it resets.

**Status: plan only. Nothing is built.** Three decisions below are the repo
owner's and are marked **OPEN**; the recommendations are what will be built
if they are confirmed. Read `../README.md`'s *The portal API* section first —
it is the contract this plan ports, and it stays the single source of truth.

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

Hence the work is **tiered**, and tier 1 is a separate decision from tier 0:

- **Tier 0** — remainder, the countdown, stale/offline honesty. No derivation,
  no cross-activation state beyond a cached reading. This is the definition
  of done.
- **Tier 1** — share of pool, tonight's top-up, free vs paid. Needs `day_pool`
  and `derive` ported, and needs the drift above stated on the face or
  designed out.

If tier 1 is wanted, it ships with **golden vectors**: a `make vectors` target
writes the numbers `derive` produces for a set of raw readings to
`windows/tests/vectors.json`, a Python test asserts the checked-in file still
matches the live `derive`, and the C# suite reads the same file. That pins the
*numbers*, which is the interface, and not the wording, which `CLAUDE.md`
deliberately leaves free.

## Decisions

### 1. Same repo, under `windows/` — **OPEN, recommended**

One portal contract, one README section, one commit when the portal moves.
The toolchains genuinely share nothing, so `windows/` is inert on a phone: no
Python import reaches into it, `make test` never sees it, and the pre-push
hook does not change (§5).

### 2. Reimplement the client in C# — **OPEN, recommended (a)**

Shelling out to Python (b) needs a Python on the Windows box an MSIX cannot
assume; a cache file written by something else (c) is (a) plus a scheduled
fetcher. (a) it is — with the tiering above, which is what keeps "two
implementations of the same thing" down to two implementations of *login and
one GET*.

The constants — 400 MiB/credit, the 763 MiB grant, the reset — get defined
once in C# with a comment naming the README section, and any change to them
is a change to both in the same commit.

### 3. Credentials: Windows Credential Locker — **OPEN, needs an answer**

`.env` is wrong on Windows. `PasswordVault` is per-user, packaged-app-native
and needs no key management; a DPAPI-protected file under
`LocalApplicationData` is the fallback if the packaging shape rules it out.
Either way the password never lands in the repo or in plaintext on disk.

What needs the owner's answer is how they are first entered, because **the
widget cannot host a text input** and the COM server has no UI:

- a minimal settings window in the same package (two fields and Save), which
  the widget's signed-out card can raise through an `Action.Execute` the
  provider handles by launching it; or
- a one-off CLI in the package, run once by hand after install.

The settings window is the one that survives a password change without the
owner remembering a command. The CLI is perhaps an hour less work.

### 4. Refresh: match the phone, which does not poll at all

Worth stating plainly, because the guidance for widgets assumes a desktop on
an unmetered line: **the Android surfaces have no background polling.** The
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

### 5. Checks: the hook does not change

No MSBuild in `.githooks/checks.sh` — it runs on Termux, where it cannot build
anything Windows, and a push here is a deploy that has to stay quick. If the
Windows half wants checks, a separate `windows-latest` GitHub Actions
workflow scoped to `paths: ['windows/**']` is the place. **That is the owner's
call** (Actions minutes, push-triggered builds) and will be asked before it is
added.

## Step 0, before any zwana code: does the platform still work here?

Microsoft's widget-provider docs are live and carry no deprecation notice, but
the platform had no Build 2026 session, and "sideloaded widget never appears
in the picker" is a recurring report — including on 26200.x, the target build
family. So the first thing built is not ours:

1. Install the unmodified `microsoft/WindowsAppSDK-Samples` C# widget provider
   on the actual machine, self-signed and sideloaded.
2. Confirm it appears in the picker, pins to the board, and updates.

Green: proceed, and the signing/sideload steps get written into the README
while they are fresh. Red: stop and re-decide the surface with the owner
before writing a line of quota code — that is a cheap afternoon against a
week spent on a board that will not show it.

## Plan of work

| phase | what | gate |
|---|---|---|
| 0 | the sample-provider spike above | go/no-go |
| 1 | `windows/` skeleton: packaged C# app, COM server, manifest, a widget that draws a constant | it pins and survives a reboot |
| 2 | the portal client (login, cookie, `GetActive`), credentials in the Credential Locker, first-run path per §3 | tier 0 on the board, real figure |
| 3 | cache in custom state, Activate/Deactivate cadence, stale/offline face | survives sign-out/in |
| 4 | README: build, signing, sideload, where credentials live | reproducible after a reinstall |
| 5 | tier 1 + golden vectors, only if §2's tiering says yes | Python and C# agree on the vectors |

## Visual

Not the terminal face. The 35 x 5 budget and the tile string budgets constrain
the Android surfaces and nothing here. The card follows the board's own theme
rather than hardcoding Catppuccin, unless the host turns out to allow colours
cleanly — which phase 1 will show.

## Out of scope

The Android surfaces, `quota_widget.py`'s face, the Tasker tile, the `dlq`
repo, and Store publishing. Sideload only.
