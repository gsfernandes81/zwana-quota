# The quota as an Android widget, and on a Garmin Instinct 3

The same reading the Termux faces show, in two new places: an Android
home-screen widget that needs nothing else installed, and a glance on a
Garmin Instinct 3, fed by the same APK acting as a Connect IQ companion.
`android/README.md` covers installing both. This file records why it is
built the way it is.

## What the watch can and cannot do

The obvious design, the watch reading `ic.zwana.io` itself, is not
possible. Connect IQ's `makeWebRequest` hands the callback a status and a
body, never the response headers, and the portal's session is the
`.AspNetCore.Identity.Application` cookie in `Set-Cookie` (README, "The
portal API", finding 2). A Monkey C app cannot hold that session. So the
phone stays the client and the watch is sent the result, over Bluetooth,
through Garmin Connect. That is the Connect IQ Mobile SDK
(`com.garmin.connectiq:ciq-companion-app-sdk`, on Maven Central).

The watch cannot ask for a reading either. The SDK has a binder service that
would let Garmin Connect wake the companion for a watch-originated message,
and its javadoc marks it "DO NOT USE without prior approval from Garmin". So
the phone pushes, on its own schedule.

## Decisions

### One APK; the widget never touches Garmin

The widget works on a phone with no Garmin Connect, no watch and no Termux.
Nothing on its path references the SDK, and the periodic send is only ever
scheduled while "send to watch" is switched on, so with it off this is a
widget and nothing else.

### The port reads the portal itself, and is held to the Python by vectors

Bridging to Termux (a `RUN_COMMAND` intent, a broadcast of `--json`) would
leave the widget needing Termux, plus a cache and staleness rules of its own
for when Termux is absent. So the app carries a Kotlin port of the client and
the derivation (`android/core`), as the Windows plan chose for the same
reasons.

A second copy of `derive` is a copy that can drift, and nothing would show
it: the `dlq` runner's guards read the Python, the widget would read the
Kotlin. So the **numbers** are pinned across the languages.
`vectors/make_vectors.py` runs the real `gather` and `derive` over fixed
inputs with the clock frozen and writes `vectors/quota.json`.
`tests/test_vectors.py`, in `make test`, fails when that file no longer
matches the live Python. The Kotlin suite replays the same file and must land
on the same numbers exactly. Only numbers and flags are pinned, never
wording, which stays free on both sides as `CLAUDE.md` asks. The file lives
at the repo root because it belongs to neither platform: the Windows port
can read it too.

Both tiers of the Windows plan are here at once, since the app was built in
one go. The headline is `Remainder` verbatim, and the share, the grant and
the free/paid split come from the ported `day_pool` and `derive`. The
Android cache's pool high-water mark starts when *it* first looks, so its
split can differ from Termux's. It differs in the safe direction (free
over-stated, never under), and only this app reads this cache.

The reset is 00:00:00 UTC, matching `next_reset`, not the README's observed
00:02:19. Fix both together or neither; the vectors would catch one alone.

### A static picture draws no relative times

A home-screen widget is redrawn only when something asks. "3h 20m" to the
reset would still say 3h 20m an hour later. So the widget's times are clock
times (`resets 05:30`, `read 14:02`) and stay true however long the picture
stands. The reset leads its own line, so an ellipsis takes the grant after it
and never the time. The watch is different: its glance is drawn when looked
at, so it computes the age itself and says `2h ago` or `new day`.

### Cadence: the tile's, not a new one

The Termux tile is not only tapped: `docs/quota-tile.md` has a **kept fresh**
profile that reads every 30 minutes. The widget's `updatePeriodMillis` and
the watch's periodic send use the same 30 minutes, plus a read on every tap.
The watch calls a reading stale at twice that. There is no WorkManager
network constraint, because a captive Wi-Fi with the quota spent is exactly
when Android calls the network unusable, and exactly when the reading
matters.

### The network the request goes over

A captive Wi-Fi that Android has not validated can leave the default route on
cellular. The portal is then either unreachable or reached over the metered
radio. Requests are bound to the Wi-Fi network when one exists, and the
diagnostics screen says which network each read used.

### What the watch is sent

One `HashMap`, built in one place (`WatchPayload` in `core/.../Face.kt`) and
tested there. The SDK carries Integer, Float, String, Boolean, List and
HashMap, and **no Long**, so byte counts go as whole KiB and times as epoch
seconds. The figures go as the strings the phone's own face draws: the
watch holds no unit ladder, no thresholds and no grades, which is how a third
language avoids a third copy.

### Credentials

`zwana_username` / `zwana_password` are entered in the app and stored with the
session cookie, AES-GCM encrypted under a key in the Android Keystore.
Backups and device transfer are off. No error message carries the request
body, which is the Python's rule, pinned by `PortalTest`.

### Diagnosable from the phone alone

Nobody using this has logcat. The settings screen shows the latest word on
the portal read (and over which network), the watch send per watch, the
worker, Garmin Connect's state, and whether the phone is holding the app
back, with the journal behind them and a **Share log** button. **Check
watch** runs from the screen itself, and **Send now** through the worker, so
a failure only in the background can be told from one everywhere.
`android/README.md` has the order to read them in.

### Builds

The Android half is built only by `.github/workflows/android-apk.yml`: the
machines this repo is written on cannot reach Google's Maven, and the phone
has no Android tooling. `android/core` is a Gradle build of its own, because
Gradle configures every project in a build before running any task, and a
`:core` beside `:app` could not be tested where the Android plugin cannot be
resolved. The APK is signed with a stable key from the repo's secrets when
they exist, so updates install over each other.

The watch app is **not** built in CI. Compiling it needs Garmin's device
definitions, which only the SDK Manager downloads after a login, and which
cannot go in a public repository.

## Not verified on hardware

Written without a phone or a watch to hand. What only a device can confirm,
and what the diagnostics screen will show if it is wrong:

- that `sendMessage` reaches a **sideloaded** watch app (Check watch, then
  Send now);
- the Instinct 3 product IDs in `garmin/manifest.xml`, and whether the Solar
  50 mm has its own;
- that the watch's background service may write `Application.Storage` (it
  also hands the message on through `Background.exit`, which covers it if
  not);
- that WorkManager's 30-minute send survives One UI's app sleeping without
  the battery exemption.
