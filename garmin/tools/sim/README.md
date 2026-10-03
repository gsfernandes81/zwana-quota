# Running the watch app in the simulator

CI compiles the watch app; it does not run it. This is how to see the pages
drawn: Garmin's simulator, headless in a container, the debug build's own
readings (`garmin/source/Fixture.mc`, left out of every release build), and a
check that nothing is drawn where the Solar does not show it.

Worked out in a cloud session (2026-10-03); it needs a Garmin login only for
the device files, the same as `.github/workflows/garmin-prg.yml`.

## Set up

1. **SDK, devices, fonts** (on the host, with `GARMIN_USERNAME` and
   `GARMIN_PASSWORD` in the environment; never on a command line):

   ```sh
   go install github.com/lindell/connect-iq-sdk-manager-cli@v0.8.4
   CIQ=$(go env GOPATH)/bin/connect-iq-sdk-manager-cli
   $CIQ agreement accept && $CIQ login && $CIQ sdk set '>=8.2.0'
   $CIQ device download --manifest garmin/manifest.xml --include-fonts
   ```

   `--include-fonts` matters: without the fonts the simulator stops at the
   first text drawn.

2. **The image**: `docker build -t ciqsim garmin/tools/sim` (start `dockerd`
   first if it is not running). Behind a proxy, give apt the proxy and its
   CA; Docker Hub may rate-limit, and `mirror.gcr.io/library/ubuntu:22.04` is
   the same image.

3. **A throwaway key** (the simulator does not care whose):

   ```sh
   openssl genrsa -out /tmp/k.pem 4096
   openssl pkcs8 -topk8 -inform PEM -outform DER -in /tmp/k.pem -out /tmp/k.der -nocrypt
   ```

4. **The container**, with the Garmin files and a work directory `/w`:

   ```sh
   docker run -d --name sim --network host -e DISPLAY=:99 -e TZ=UTC \
     -v ~/.Garmin:/root/.Garmin -v "$PWD/garmin/tools/sim":/t -v /tmp/sim:/w ciqsim \
     bash -c 'Xvfb :99 -screen 0 1280x1024x24 & sleep infinity'
   ```

## Run

Build a debug `.prg` into `/tmp/sim` (no `-r`: the readings are debug-only),
then load it and drive it:

```sh
monkeyc -f garmin/monkey.jungle -d instinct3solar45mm -y /tmp/k.der -o /tmp/sim/solar.prg
docker exec sim /t/fresh.sh instinct3solar45mm /w/solar.prg 325,319
docker exec sim /t/keys.sh shot:data key:41,360 wait:1.5 shot:connection
python3 garmin/tools/sim/clipcheck.py /tmp/sim data connection
python3 garmin/tools/sim/sheet.py /tmp/sim /tmp/sim/sheet.png Data=data Connection=connection
```

Each opening loads the first reading; holding UP (MENU) loads the next, the
last being none at all. Clicks land on the buttons of the device picture,
and the screen is cropped out of the window:

| | Solar 45 mm | AMOLED 45 mm |
|---|---|---|
| START | `357,210` | `600,331` |
| UP (hold: MENU) | `19,272` | `34,437` |
| DOWN | `41,360` | `65,578` |
| BACK | `354,333` | `599,543` |
| the notice's OK | `325,319` | `325,483` |
| `CROP` | `176x176+104+186` | `390x390+123+245` |

Pitfalls met: the simulator's glance mode (Settings, Glance Launch Mode)
shows the glance loop instead of the app, and `fresh.sh` sets it back; a menu
left open swallows every click; `pkill -f bin/simulator` from inside
`bash -c` kills its own shell first. A crash prints to `/w/do.log`.

## What the checks are for

`clipcheck.py` is the one to run after any change to a page: a pixel drawn
past the Solar's cut corners or under the sub-window's lens is not drawn, and
the page reads clipped (the R of `RESET`, once, on rows placed by the
mockup's octagon rather than the screen's). The page code keeps to the screen
by the same numbers (`PageDraw.edges`, `PageDraw.CUT`); the check is what
says the numbers are right.
