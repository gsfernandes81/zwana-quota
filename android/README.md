# zwana quota for Android, and on a Garmin Instinct 3

One APK, two jobs:

- **a home-screen widget**: what is left of today's data, and when it resets,
  with a switch for the data connection and who is on it. It reads the portal
  itself and needs nothing else installed: no Termux, no Garmin Connect, no
  watch.
- **a Connect IQ companion**: when switched on, it sends the same reading to
  the zwana quota app on a Garmin Instinct 3 (`../garmin/`), which shows it
  as a glance.

The decisions behind it are in `../docs/android-widget.md`. This file covers
getting it onto the phone and the watch.

## Layout

| path | what |
|---|---|
| `core/` | the portal client, `gather`/`day_pool`/`derive`, the widget's face, the watch payload, the data session and its switch (`Session.kt`), and the device-name lookups (`Names.kt`). Plain Kotlin on the JVM, no Android, **a Gradle build of its own** so it can be tested anywhere Maven Central is reachable. Held to `../vectors/quota.json`, which the Python writes |
| `app/` | the Android half: the widget and its "turn data off?" dialog, the settings/diagnostics screen, the background worker, the Garmin SDK. Built only by CI |
| `../garmin/` | the Monkey C watch app. Built by `../.github/workflows/garmin-prg.yml` (below) |
| `../.github/workflows/android-apk.yml` | the only compiler the Android half has |

`make android-test` runs the core's tests. `make test` does not: the phone
that pushes has no JDK. It does run `tests/test_vectors.py`, which fails when
the Python's derivation changes and `vectors/quota.json` has not been
regenerated (`make vectors`). A regenerated file then fails the Kotlin suite
until the same change is made in `core/`. That is the point: two copies of
the derivation, held to one set of numbers.

## Installing the APK

1. Download `zwana-quota.apk` from the newest release:
   <https://github.com/gsfernandes81/zwana-quota/releases/latest/download/zwana-quota.apk>. Every build of `main` that passes is published as a
   release by itself (`build-N`, N the commit count, which is also the APK's
   version code), with its `.sha256` and `signer.txt`, which says which key
   signed it. There are no versions to choose and no tags to push; the five
   newest builds are kept.
2. On the phone, open the APK and allow the installer to install unknown apps
   when asked.
3. Open **zwana quota**, enter the portal login (the same `zwana_username` /
   `zwana_password` as the `.env`), and press **Save and read**.
4. Long-press the home screen → Widgets → **zwana quota**. It reads the
   portal every 15 minutes while the screen is on, and at once when tapped.

**Updates** install over the old APK, since every build has a higher version
code and the same key. To have them arrive by themselves, add this repository
to [Obtainium](https://github.com/ImranR98/Obtainium), which watches its
releases and installs each new APK.

### The data switch and the device list

Once the portal has been read, a switch sits beside the figure:

- **● Data on**: this phone is on the data session. Tapping asks first, then
  turns data off for **every device** if this phone switched it on (as the
  portal's own switch does), or takes **only this phone** off if it joined
  someone else's session.
- **○ Data off**: tapping switches data on, with this phone as the one that
  switched it on. No question asked.
- **+ Join**: data is on for another device and not this one. Tapping joins.

Before anything is sent, the session is read again. If it changed since the
widget was drawn (someone else switched data off, say), nothing is sent and
the footnote says `changed elsewhere: nothing done`.

Beside the share, where the widget is wide enough, is how much of what is
left is paid data, in whole MiB whatever its size (`412 MiB paid`). It is
the part that gives way on a narrow widget; the share and any warning on it
do not. Like the Python's `paid.left_bytes` it is a floor: paid data that
carried over midnight unseen can only make the true figure larger.

The line under the reset says who is on: `This phone + 1 device`. Resized to
about 4 x 3 cells (Android 12 and later), the widget lists them instead, up
to three rows. Each device is shown by the name the ship's network gives it,
else by its IP, never its MAC. The name is asked three ways: reverse DNS from
the Wi-Fi's DNS server, mDNS from the device itself (phones, Macs, Linux),
and NetBIOS (Windows). A network that keeps its clients apart answers none of
them, and the IPs are what you see.

The login is kept only on this phone, encrypted with a key in the Android
Keystore. Backups and device transfer are switched off, so it does not travel.

### Signing, so updates install over each other

Android only installs an update signed with the same key as what is already
installed. Without a key in the repository's secrets, each CI run mints its own
and warns, and every install becomes uninstall-then-install: the widget has
to be placed again and the login entered again. To make it stable, once, in
Termux (`openssl-tool` is a few MB, where `openjdk-21` for `keytool` is far
more over a metered link; the result is the same PKCS12 keystore):

```sh
pkg install openssl-tool
cd ~/zwana-quota                            # the checkout: keys here are gitignored
openssl req -x509 -newkey rsa:4096 -keyout zwana-key.pem -out zwana-cert.pem \
        -days 10000 -nodes -subj "/CN=zwana quota"
openssl pkcs12 -export -in zwana-cert.pem -inkey zwana-key.pem \
        -name zwana -out zwana.p12          # Enter twice for no password
rm zwana-key.pem zwana-cert.pem             # the .p12 holds both now
base64 -w0 zwana.p12                        # the value of ANDROID_KEYSTORE_BASE64
```

Add it as a secret of the `garmin` environment (Settings → Environments →
garmin), beside the Garmin ones. The APK job runs in that environment, but a
job is handed only the secrets its steps name, so it never sees the Garmin
login:

| name | value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the `base64 -w0` output; the only one needed |
| `ANDROID_KEYSTORE_PASSWORD` | only if you gave `openssl pkcs12` a password |
| `ANDROID_KEY_ALIAS` | only if you used a `-name` other than `zwana` |
| `ANDROID_KEY_PASSWORD` | only if it differs from the keystore's |

A keystore without a password is protected by where it is kept and nothing
else: here, the gitignored checkout on the phone and a repository secret.
CI's own fallback key is made the same way, so every build without the
secret also proves that a password-less keystore signs.

The repository is public, so the keystore reaches CI only as secrets. In the
checkout it is safe: `*.p12`, `*.pem`, `*.der`, `*.jks` and `*.b64` are all
gitignored, anywhere in the tree. Keep `zwana.p12` and its password somewhere safe off
the phone: losing either means one more uninstall. The first build signed
with it will not install over a build signed with a minted key; uninstall
once, and every build after installs in place.

## The watch

### What it shows

The glance is laid out as Garmin's Body Battery glance is: a title and,
after a reset icon, when tonight's grant lands; a bar whose thick part is what
is left and thin part what has gone (full at the reset); and under it what is
left and, beside it, how much of it is paid:

```
DATA LEFT       ↻@00:00
████████━━━━━━━━━━━━━━
301 MiB      0 MiB paid
```

When the reading cannot be taken at face value, the reason takes the paid
figure's place beside what is left, or the title's if it does not fit there:

- `new day`: the reset has passed since the reading. The figure is
  yesterday's, and the grant it does not count has already landed.
- `2h ago` (the reading's age; `60m ago` first): the reading is more than two
  send intervals old (an hour) -- nothing fresh has come, whether the phone or
  the portal is out of reach.
- `offline`: the portal said the session was down when it was read.
- `no time`: the reading carries no time, so its age cannot be told.

Where even the title's place is too narrow for the word, a short form is
drawn instead: `old` where the figure is out of date (`new day`, `2h ago`),
`!` where it cannot be vouched for (`offline`, or a reading with no time).

The watch works out its staleness from the reading's own timestamp. It never
believes the phone's "live" flag, which was true when it was sent and says
nothing about now.

Opening the glance shows the app, in pages: UP and DOWN (or a swipe) slide
between them in the watch's own page loop, with its own page indicator;
START does the page's one thing, and the round sub-window (top right on the
Solar, beside START) shows each page's one number or what START does.

| page | shows | sub-window | START |
|---|---|---|---|
| **Data left** | the figure large, the bar, the reset time, how much of it is paid (`412 MiB paid`) | the share left, as a ring | a fresh reading (with **Let the watch ask**) |
| **Connection** | ON or OFF, how this phone stands, how many devices | the power symbol when START can switch | switch data (with **switch data** on) |
| **Device**, one per device | its name, as large as it fits, and whether it switched data on or joined | which of how many (`2/3`) | take that device off, when it is one that can be |

The session pages appear once the phone has read the session: one page per
device, this phone first, up to eight (the last says how many more). Anything
that takes a device off data asks first, in the watch's own confirmation.

### Building the watch app

CI builds it: `.github/workflows/garmin-prg.yml` signs in to Garmin, downloads
the SDK and only the Instinct 3 device files, and compiles one `.prg` per
model. Garmin's device files need a login and cannot be committed to a public
repository, which is why the login is involved at all.

**Once, set up the `garmin` environment.** Repository Settings → Environments
→ New environment, named exactly `garmin`. Environment secrets rather than
repository ones, so that only this one job can read the Garmin login: not the
APK build, and not any other workflow. If you restrict its deployment
branches, include the branch you build from.

| name | kind | value |
|---|---|---|
| `GARMIN_USERNAME` | environment secret | the Garmin account's email |
| `GARMIN_PASSWORD` | environment secret | its password |
| `GARMIN_DEVELOPER_KEY` | environment secret | `base64 -w0 developer_key.der` (below) |
| `GARMIN_AGREEMENT_HASH` | environment variable, optional | pins the SDK licence; the job's log prints the current hash |
| `GARMIN_SDK_VERSION` | environment variable, optional | an SDK version or range; default `>=8.2.0` |

Use a Garmin account **with two-step sign-in off**: the CLI signs in with the
password alone and cannot answer a second step. A separate account kept for
this is better than your own, since the secret is the full password rather
than a limited token. Any account works: the SDK and device downloads are not
tied to who you are, and neither is the app.

**Once, make the developer key**, and keep a copy somewhere safe. The same key
must sign every build that is to install over the last, so losing it means
uninstalling the watch app to install the next one. `openssl` is in Termux
(`pkg install openssl-tool`):

```sh
cd ~/zwana-quota          # the checkout: *.pem and *.der are gitignored
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem \
        -out developer_key.der -nocrypt
base64 -w0 developer_key.der          # the value of GARMIN_DEVELOPER_KEY
```

**Each build** is in the newest release, beside the APK: a push to `main`
that touches `garmin/`, `android/` or `vectors/` builds both and publishes
them together (`.github/workflows/release.yml`). The Solar 45 mm's is
<https://github.com/gsfernandes81/zwana-quota/releases/latest/download/zwana-quota-instinct3solar45mm.prg>; there is one per other model, with
`SHA256SUMS` and the key's fingerprint. A push to any other branch builds it
as a check only, kept a few days as the run's artifact. Without the secrets
the job says which are missing and stops, green, and the release holds the
APK alone.

**Install over USB**:

1. Connect the watch by USB and copy the `.prg` for your model (the Solar
   45 mm: `zwana-quota-instinct3solar45mm.prg`) into `GARMIN/APPS/`. Windows
   shows the watch as a device in Explorer; from an Android phone it needs an
   MTP app.
2. Unplug, then **open the app once** from the watch's apps list. Opening it
   is what registers for the phone's messages; until then nothing arrives,
   and it looks exactly like a dead link. Then add it to the glance loop
   (Glances → Add → zwana quota).

An update is the same: copy the new `.prg` over the old one.

**Without CI**, on a desktop with Garmin's SDK Manager signed in and the
Instinct 3 devices downloaded (a few hundred MB, so somewhere unmetered):
`monkeyc -f garmin/monkey.jungle -d instinct3solar45mm -y developer_key.der
-o zwana-quota.prg`, or *Build for Device* in the VS Code Monkey C extension,
whose simulator also runs the glance without a watch.

### Connecting the two

In the app, switch on **Send the reading to the watch**. It sends one
straight away, then every 15 minutes while the phone's screen is on and at
least every 30 minutes while it is off, and on every tap of the widget.
Switched off, the SDK is never touched.

**Asking from the watch.** Switch on **Let the watch ask for a reading** as
well, and START on the watch's first page asks the phone for a fresh
reading, usually within ten seconds (`START: refresh` at the bottom says
so). The watch says `asking phone` meanwhile, and `no answer from phone`
after a minute. With the setting off the watch shows none of this.

**Switching from the watch.** Beneath it, **Let the watch switch data and
disconnect devices** (off by default) lets START on the Connection page
switch data -- off for every device when this phone switched it on, only
this phone when it joined, as the widget does -- and START on another
joined device's own page take it off. The watch asks first; the phone then
checks the request against the portal before sending anything, and does
nothing if the session changed. This phone and the device that switched data
on are never offered for removal. It keeps a small listener running on the phone (Garmin Connect only
delivers a watch's message to an app that is running), so set the app's
battery use to Unrestricted or One UI may stop it; the diagnostics' "Watch
asks" row says whether it is listening.

## First install: what to check, in order

The settings screen is the diagnostics screen: nobody using this has logcat,
so everything that can fail says so there. **Share log** sends all of it as
text. Work down this list:

1. **The widget draws.** It says `quota ?` / `no reading` until the login is
   saved, then the figure. `read` on the diagnostics screen says which
   network it went over. It should be Wi-Fi, since the portal is on the
   vessel's Wi-Fi.
2. **Check watch.** Each line isolates a different failure:

   | what it says | what is wrong |
   |---|---|
   | `Garmin Connect is not installed` / `needs updating` | exactly that |
   | `did not answer (SERVICE_ERROR)` | Garmin Connect is installed but not signed in, force-stopped, or battery-restricted |
   | `no watch is paired` / `NOT_CONNECTED` | pairing or Bluetooth, nothing to do with this app |
   | `CONNECTED, watch app NOT installed` | the `.prg` is not on the watch, or its manifest `id` differs from `Garmin.APP_ID` |
   | `CONNECTED, watch app INSTALLED` | the link is good |

3. **Send now**, then read `watch` on the diagnostics screen:
   `<watch>: sent`. The watch's glance should then show the reading. If it
   says `sent` but the glance stays on `quota ?`, the watch app was not
   opened once after installing (step 5 above).
4. **Leave it.** After an hour, `worker` should have advanced by itself. If it
   has not, the phone is holding the app back: the `battery` line says so, and
   **Battery: let it run in the background** is the way out. On Samsung phones,
   also take it out of *Sleeping apps*.

The widget depends on none of steps 2 to 4.
