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
- **`android/` is a second copy of the client and the derivation, held to
  this one by numbers.** The Android widget reads the portal itself (it must
  work with nothing else installed), so `android/core` ports `gather`,
  `day_pool` and `derive` to Kotlin. `vectors/quota.json` is what the live
  Python makes of fixed inputs; `tests/test_vectors.py` fails `make test` when
  a change here leaves it stale (`make vectors`), and the Kotlin suite fails
  until the port lands on the new numbers. **Change the derivation here, carry
  it there, in the same push.** `docs/android-widget.md`.
- **The watch gets strings, never rules.** `garmin/` (Monkey C, Instinct 3)
  is sent the figures the phone already spelled, as one payload built in
  `WatchPayload` — no Long in the Connect IQ SDK, so KiB and epoch-second
  Ints. It decides only what only it can: whether the reading is still
  believable *now* (`new day`, `2h ago`). The payload's keys are the wire
  contract; renaming one blanks the glance without an error anywhere.

## Checks

`make test` (pytest) = `make check` (`.githooks/checks.sh`, the one copy; the
pre-push hook runs it). Offline: the portal is never reached. This repo needs
no sibling checkout — it is the one the others reach down into. Exit 5 is a
failure again: with a suite in the tree, "no tests collected" means the tests
did not import, which is not a state to push a deploy in.

`make android-test` is the Kotlin port's suite (`android/core`, JDK and
Gradle, Maven Central only) and is **not** in `make check`: the phone that
pushes has neither. CI runs it before building the APK
(`.github/workflows/android-apk.yml`), which is the only place the Android app
is compiled. The watch app is compiled by `.github/workflows/garmin-prg.yml`,
which signs in to Garmin with the `garmin` environment's secrets (the device
files need a login and cannot be committed) and does nothing without them.

`quota_widget.py --self-test` came out on 2026-09-02 and `tests/` replaced it
on 2026-09-03. Not a transcription of it: the suite tests **behaviour and
invariants**, not output, so the wording, the ladders and the layouts are all
free to move under it. A test that would have to be edited to change a phrase
is a test that stops the phrase being changed.

**Nothing in the suite reaches the portal, and nothing outside `tmp_path` is
written.** Three autouse fixtures in `tests/conftest.py` see to it and are the
whole of the arrangement: `no_network` makes a socket, `urlopen` or the shared
opener an outright failure; `sandbox` points the cache, the lock, the cookie
jar, the probe log and `.env` — all module-level `Path`s off `Path.home()` —
at a temporary home; `spawned` replaces `subprocess.Popen`, so the detached
refresher is recorded rather than started. The portal is stubbed at the seams
the modules already have — `zwana_quota.request` for the client,
`zwana_quota.fetch` for the widget — with documents built from the field names
the code itself reads. **Nothing was added to either module to make this
possible**, which is the point: a seam invented for a test is a seam nobody
uses.

| file | what it pins |
|---|---|
| `tests/conftest.py` | the three fixtures above, the frozen `clock`, the portal documents, and `parse_size` — which reads a formatted figure back and says how far out it may be |
| `test_portal_client.py` | credentials (environment beats file, and no error ever echoes the password), the HTTP seam (302 is `NotAuthenticated`, anything else a `PortalError`), one retry and only one, the session cookie's round trip and its 0600, the credit rate |
| `test_gather.py` | the three calls, the grant read rather than assumed, today's draws against yesterday's, and a lost history keeping today's grant instead of zeroing it; `day_pool` as a high-water mark that never goes negative and never survives the reset |
| `test_derive.py` | the derivation's guarantees, as properties: free + paid is the remainder, free never exceeds the grant, and **more hidden carry-in can only lower free and raise paid** — which is `free.left_bytes` being an upper bound and `paid.left_bytes` a lower one, stated over every input rather than at one figure |
| `test_cache.py` | a stale reading is never handed back as live, the max-age boundary, an unreadable cache being no reading rather than a crash, and the refresh lock: one at a time, abandoned after `LOCK_TIMEOUT`, and never left behind by a spawn that failed |
| `test_format.py` | the size, money, age and countdown spellings by round trip — the figure has to be true to the precision it printed — the reset ahead and within a day at six timezones either side of the date line, and the three grades |
| `test_tile_face.py` | the face: five rows, never wider than `TILE`, ASCII only, at every unit threshold and every digit-gaining value; the reset time surviving every squeeze; a stale or offline reading *drawn* and not merely fitted |
| `test_qs_tile.py` | the four lines in the order Tasker splits them into, the label and subtitle budgets at all three named sizes, the state never Android's third one, and the level word for the icon |
| `test_full_box.py` | `--full`: nothing but ASCII and the one measured middot inside the frame, no row padded out with spaces, the bar's filled run being the share that has gone, and the countdown giving way before the clock time does |
| `test_vectors.py` | `vectors/quota.json` is still what the live `gather` and `derive` produce, and building it leaves the clock and the portal stub as it found them |
| `test_cli.py` | both command lines: which stream the answer lands on, the exit codes, that calibration costs no request, that the tile printed is the tile composed, and that `--refresh-only` says nothing and leaves a good cache alone when it fails |

**`_fake` and `TILE_ROWS` did not come back, and should not.** The fake
document is `conftest.document()`, which builds a synthetic raw reading and
runs it through the *real* `derive` — so a document that could not come out of
the pipeline cannot be handed to a renderer either, which a hand-written one
could. The face's height is `GLYPH_ROWS`, which the module already spells;
`TILE_ROWS` was a second copy of it. Neither belongs in a module the widget
ships.

**The bounds the fits are checked to** are the magnitudes the figures reach,
not infinity, and each is stated where it is used: the tile face to 32 TiB,
the Quick Settings label to 8 TiB (below which the 5-character small tile
still has a rung), and the `--full` box to 10 GiB at 30 columns or wider —
its headline states the figure *twice*, so a hundred-gibibyte reading wants
more columns than the box has. A day's pool is 763 MiB.

**The checks worth not weakening**: the upper/lower bound property in
`test_derive.py` (it is what the `dlq` runner's guards stand on), the
four-line order in `test_qs_tile.py` (Tasker addresses them by position, so
reordering silently relabels the tile rather than failing), and the
stale-or-offline tests in both tile files — those check the warning is
*drawn*, which is the failure the widths exist to prevent.

## Mutation testing

`make mutants` — poodle, out of `make test` and out of the pre-push hook on
purpose: a push is a deploy and the checks have to stay quick. `poodle_config.py`
carries the flat-root layout (`source_folders = ["."]`, poodle's default of
`src`/`lib` would find nothing), mutates only `quota_widget.py` and
`zwana_quota.py`, and keeps the cache, the cookie jar and `.env` out of the
copy every worker gets. It takes about forty minutes on four idle cores:
1,851 mutants, one suite each.

Where it stands (2026-09-03): **1,624 of 1,851 caught, 87.7%**, no timeouts
and no errors. The suite that replaced the self-test started at 76.9%; the
gap was closed by reading the survivors, not by adding tests until the number
moved.

It changes one thing in one module and runs the suite against it; a mutant the
suite still passes is a behaviour nothing pins. **Read the survivors, not the
score** — the score is a ratchet, not a gate, because a good part of what
survives here is meant to.

Two `# nomut` fences, and only two: the shared `urllib` opener (the one live
network object in the repo, replaced wholesale by the suite) and the
`__main__` blocks. Everything else is mutated, including every ladder and
every threshold.

The suite notices poodle's `MUT_SOURCE_FILE` in its environment and turns the
property tests down to a smaller **derandomised** sample — a mutation run is a
few thousand suites rather than one, and a survivor then has to be a survivor
rather than a sample that happened to miss. That reduction is also what the
first run taught: a property spread over eight tebibytes lands in the
mebibyte band perhaps never in twenty-five draws, so **every size ladder has a
parametrised list of the thresholds beside its property**. Wide random input
finds the shape of a rule; named values are what hold its edges.

What survives, and why each is meant to:

- **Wording.** Every message, every `--help` string, every `json.dumps(indent=)`,
  every label on the probe's glyph pages. Roughly half of what is left. The
  suite deliberately pins no phrasing, so mutating one changes nothing it can
  see — that is the whole trade the suite was rebuilt to make. The exceptions
  are the phrases that are an *interface*: `active`/`inactive`, `ok`/`low`/
  `critical`, and `quota ? / no reading`, which are pinned because Tasker and
  `docs/quota-tile.md` are written against them.
- **Paths off `Path.home()`** — `CACHE`, `LOCK`, `PROBE_LOG`, `COOKIE_FILE`,
  `DEFAULT_ENV`, and `sys.path.insert`. The suite points them at a temporary
  home, so their spelling cannot be checked without writing to the person's
  real one. Untestable hermetically, and the hermeticity is worth more.
- **Equivalent mutants**, which no test can kill: `GLYPHS[c][0]` → `[1]`
  (every row of a glyph is the same width, which is itself checked);
  `" " * max(0, n)` → `max(-1, n)`; `unlink(missing_ok=)` where the file was
  just stat'd; `os.close(fd)` dropped; `int(x / 60)` → `x // 60` for a value
  that cannot be negative; `zip(..., strict=False)` over two lists that are
  always the same length; `max(1, pool_bytes)` in the three renderers, since
  `derive` already guarantees it; and `human_bytes`' final `return`, which
  **is dead code** — the loop always returns at `TiB`.
- **`fit()`'s last-resort slice** in `compose_tile`, which is unreachable at
  any width the tile is drawn at — it exists so that a width the function
  cannot satisfy still cannot overflow, and the function's own docstring says
  so.
- **`track = max(4, inner - len(pct) - 4)`** and the head's `gap`, to within a
  column. The checks that matter hold across them — the bar fits, spans at
  least half the row, is the same length however full, and its filled run is
  the share that has gone — and pinning the arithmetic itself would be copying
  the line into the test.
