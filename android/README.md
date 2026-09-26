# zwana quota for Android, and on a Garmin Instinct 3

One APK, two jobs:

- **a home-screen widget**: what is left of today's data, and when it resets.
  It reads the portal itself and needs nothing else installed: no Termux, no
  Garmin Connect, no watch.
- **a Connect IQ companion**: when switched on, it sends the same reading to
  the zwana quota app on a Garmin Instinct 3 (`../garmin/`), which shows it
  as a glance.

The decisions behind it are in `../docs/android-widget.md`. This file covers
getting it onto the phone and the watch.

## Layout

| path | what |
|---|---|
| `core/` | the portal client, `gather`/`day_pool`/`derive`, the widget's face and the watch payload. Plain Kotlin on the JVM, no Android, **a Gradle build of its own** so it can be tested anywhere Maven Central is reachable. Held to `../vectors/quota.json`, which the Python writes |
| `app/` | the Android half: the widget, the settings/diagnostics screen, the background worker, the Garmin SDK. Built only by CI |
| `../garmin/` | the Monkey C watch app. Built on a machine with the Connect IQ SDK (below) |
| `../.github/workflows/android-apk.yml` | the only compiler the Android half has |

`make android-test` runs the core's tests. `make test` does not: the phone
that pushes has no JDK. It does run `tests/test_vectors.py`, which fails when
the Python's derivation changes and `vectors/quota.json` has not been
regenerated (`make vectors`). A regenerated file then fails the Kotlin suite
until the same change is made in `core/`. That is the point: two copies of
the derivation, held to one set of numbers.

## Installing the APK

1. Open the latest green run of **android apk** on the Actions tab, and
   download the `zwana-quota-android` artifact. It holds `zwana-quota.apk`,
   its `.sha256`, and `signer.txt`, which says which key signed it.
2. On the phone, open the APK and allow the installer to install unknown apps
   when asked.
3. Open **zwana quota**, enter the portal login (the same `zwana_username` /
   `zwana_password` as the `.env`), and press **Save and read**.
4. Long-press the home screen → Widgets → **zwana quota**. Tap it to read again.

The login is kept only on this phone, encrypted with a key in the Android
Keystore. Backups and device transfer are switched off, so it does not travel.

### Signing, so updates install over each other

Android only installs an update signed with the same key as what is already
installed. Without a key in the repository's secrets, each CI run mints its own
and warns, and every install becomes uninstall-then-install: the widget has
to be placed again and the login entered again. To make it stable, once:

```sh
keytool -genkeypair -keystore zwana.jks -storetype PKCS12 -alias zwana \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=zwana quota"
base64 -w0 zwana.jks            # the value of ANDROID_KEYSTORE_BASE64
```

In the repository's Settings → Secrets and variables → Actions, add
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
(`zwana`) and `ANDROID_KEY_PASSWORD` (the same password, for a PKCS12
store). The repository is public, so the keystore only ever lives in secrets,
never as a file in the tree. Keep `zwana.jks` somewhere safe: losing it means
one more uninstall. `keytool` comes with any JDK, including Termux's
`openjdk-21`.

## The watch

### What it shows

The glance shows the figure, and under it the reset time, with the share of
today when there is room:

```
1.68 GiB
95% left, resets 05:30
```

When the reading cannot be taken at face value, the reason replaces the
share, and the reset time stays:

- `new day`: the reset has passed since the reading. The figure is
  yesterday's, and the grant it does not count has already landed.
- `2h ago`: no reading has arrived for more than two send intervals (an hour).
- `offline`: the portal said the session was down when it was read.

The watch works out its staleness from the reading's own timestamp. It never
believes the phone's "live" flag, which was true when it was sent and says
nothing about now. Opening the glance shows the full view: the share of
today, tonight's grant, when it was read, and a message number, so a new send
can be told from the last.

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
openssl genrsa -out developer_key.pem 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem \
        -out developer_key.der -nocrypt
base64 -w0 developer_key.der          # the value of GARMIN_DEVELOPER_KEY
```

**Each build**: any push that touches `garmin/`, or **Run workflow** on the
**garmin prg** workflow in the Actions tab. Its `zwana-quota-garmin` artifact
holds `zwana-quota-instinct3solar45mm.prg` (and one per other model),
`SHA256SUMS`, and the key's fingerprint. Without the secrets the job says
which are missing and stops, green.

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
straight away, then every 30 minutes (the same cadence as the Tasker tile's
"kept fresh" profile) and on every tap of the widget. Switched off, nothing
is scheduled and the SDK is never touched.

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
   `<watch>: sent`. The watch's full view should then show a new `#n`. If it
   says `sent` but the glance stays on `quota ?`, the watch app was not
   opened once after installing (step 5 above).
4. **Leave it.** After an hour, `worker` should have advanced by itself. If it
   has not, the phone is holding the app back: the `battery` line says so, and
   **Battery: let it run in the background** is the way out. On Samsung phones,
   also take it out of *Sleeping apps*.

The widget depends on none of steps 2 to 4.
