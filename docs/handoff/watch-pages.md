# Handoff: fuller watch pages (built; the design as approved, and where the build departs)

Designed 2026-10-03 and approved by the owner from mockups; **built the same day** on
`claude/relaxed-turing-uvp7cg` (from `claude/nice-turing-uwez0g`) and checked page by page
in the simulator (`garmin/tools/sim/README.md`), Solar and AMOLED 45 mm. Not yet on a watch.
Merging to `main` publishes release `build-N`, so it waits for the owner's go-ahead.

## Where the build departs from the design below, and why

Every departure comes from the real screen and fonts, which the mockups stood in for:

- **Rows 6 px higher, their column from the screen.** The Solar's corners are cut 37 px
  along each edge (measured), which takes the ends off a row centred at y = 152: the R of
  `RESET` lost pixels. The figure now stands on 78, the bar spans 85..93, the rows sit at
  106/126/146, and every row and rule shares one column, the width the lowest row's ink is
  seen at (`PageDraw.edges`). `garmin/tools/sim/clipcheck.py` fails any shot with a lit
  pixel at the screen's edge or under the lens.
- **The bar takes the rows' column** (2 px further left), so it lines up with them.
- **The countdown has a third spelling, whole hours (`21:36 · 3h`).** The real fonts are
  wider than the mockup's: `21:36` is 41 px and `3h12m` 49, against 75 of room. The clock
  time beside it carries the minutes.
- **Titles: `DATA` and `ONLINE` on the Solar.** The existing lens margin (a title drawn up
  to the reported circle lost a letter on the watch) leaves 68 px; `DATA LEFT` is 83 and
  `CONNECTION` 99, `INTERNET` 75. The AMOLEDs draw the long ones. By these widths the old
  Connection page drew no title on the Solar at all.
- **ON/OFF in `FONT_NUMBER_HOT` on the Solar only.** Its number fonts hold O, N and F; the
  AMOLEDs' draw boxes. `monkey.jungle` says which watch is which (`numberWords`).
- **The list's icons are drawn, not bitmaps.** A `BitmapResource` as an `IconMenuItem`
  icon ends the app; a `WatchUi.Bitmap` works but lands at the sub-window's top-left
  without its white disc. A `Drawable` (`DeviceIcon`) is handed a 62 x 62 canvas for the
  sub-window and a 24 x 14 one beside the name, and draws the sub-window as every page
  does, and the small phone or laptop beside the name.
- **`share` is no longer sent**: nothing on the watch draws the percentage now, and the
  contract test holds the phone to sending only what the watch reads. `free` is new.
- **Found on the way:** `WatchUi.getSubscreen` does not exist on the AMOLEDs, and calling it
  unguarded ended the app as it opened there, before this change too. Now guarded with
  `has`.

- **Glyphs are bitmaps** (after build-92, which felt heavy on the Solar: slides showed
  about two frames). Drawing them run by run from `Glyphs.mc`'s rows was ~80% of a page's
  draw in the simulator's profiler; as bitmap resources (`garmin/tools/glyphs.py` writes
  the PNGs) a page draws about 7x faster, the app's memory fell from 43.8 to 31.4 kB, and
  the release `.prg` from 29.9 to 25.3 kB. The list's rows no longer draw the small device
  icons (a quarter size and off-centre on the watch); the focused device's icon stays in
  the sub-window.

Still for a watch to settle: whether `onWrap` fires the same on the watch as in the
simulator (it does there, both ends), and how the native list looks on the AMOLEDs' touch.

Mockups (Instinct 3 Solar, 176 x 176, drawn 1 px = 1 px, shown at 3x):

- `watch-pages-refined.png`: the approved Data and Connection pages, and the native list
  for Device. Its first two Device panels ("in the loop: who is on") are **superseded**
  by the hand-over behaviour below.
- `watch-pages-first.png`: the first round. Only the **Device "A"** panel (big device
  glyph, name, role, pips) still matters: it is the fallback if the native list
  cannot hand control back. The ring designs on it were **rejected**: the Solar's screen
  is an octagon with flat edges, so a bezel ring is wrong there.

Why: the owner found the slide between pages abrupt, rejected lines at the screen's edge,
and asked that the pages fill the screen more, so the slide reads as the page moving.

## Data left: "B, big figure + stat rows" (approved: "Good... I like this very much")

Layout on the Solar (y is the baseline or centre as noted, x in px):

- Title `DATA LEFT` at (26, 30), left, vertically centred. When the reading is old
  (`Quota.mark` gives `2h ago` / `new day` and so on) the title is **replaced by an inverted
  tab**: a filled rounded rect from (22, 22) to (22 + text width + 12, 38), radius 8, with the
  mark in background colour at (28, 30). The warning must be *drawn* where the eye starts,
  and the reset row must still show.
- The figure: number in `FONT_NUMBER_MEDIUM` at x = 12, baseline 84; unit in `FONT_TINY`
  placed at **number's measured width + 4**, bottoms level. The unit is never under the
  number; the mockup overlapped once because it was placed at a fixed x. Ladder: if
  number + unit would reach the sub-window's keep-out (circle r = 31 + 4 at (143, 36)),
  step the number font down one size. The mockup shows `1023 MiB` stepped down. Below
  that, fall back to the existing `FONT_MEDIUM` whole-figure path.
- The bar: **10 segments, 13 px wide, 2 px gaps, 148 px total, x = 14..161**, y = 91..99.
  Filled = share left. Hollow = a 1 px outline. Clamp at 10 (paid can push the share over
  100%). Do not show 0 filled while anything is left.
- Three rows at y = 112, 132, 152 (vertical centres). Label in a small bold font at x = 16,
  left; value at x = 161, right. Rows: `FREE` / `PAID` / `RESET`. The reset value is
  `00:00 · 3h 12m` (clock time · countdown). Between rows, a **dotted** 1 px rule (every
  other pixel) at y + 10 from x = 16 to 161: the screen is 1-bit, so a solid rule reads
  heavy and there is no grey.
- **"Pixel perfect" treatment** (the owner's words: "Give it the same pixel perfect
  drawing treatment as your beautifully done icons"): every rectangle, segment and rule on
  integer pixels with the exact sizes above; the system fonts are bitmap fonts already. The
  `START: refresh` line goes: the refresh glyph in the sub-window already says it.
- **Wire change needed:** `FREE` is not sent today. The payload has `fig`, `share` and
  `paid` (`Face.kt`, around `"paid" to Format.mib(doc.paidLeftBytes)`). The watch gets
  strings, never rules, so it must not compute fig − paid: add a `free` key spelled by
  the phone (`Format.mib` of the free bytes), read it on the watch, and keep
  `tests/test_watch_contract.py` passing (sent ⇔ read). Hide the row when an older phone
  app does not send it, as `paid` already is.
- Check the overlap against **lit pixels**, not text boxes: the mockup's check against the
  keep-out circle false-positived on boxes.

## Connection: "A without the ring" (approved: "perfect")

- Title `CONNECTION` at (26, 30). Big `ON` / `OFF` (`FONT_NUMBER_*` or `FONT_LARGE`,
  whichever fits) centred at (88, 90). It must clear the sub-window keep-out; at y = 84 the
  `OFF` touched it by 8 px. `dsub` (for example `switched on here`) centred at y = 117;
  when off, `for every device`.
- A row of device icons standing on baseline y = 156, centred, this phone first (the
  order of `dn`):
  - **1–4 devices:** the full-size glyphs (`Glyphs.PHONE`, `Glyphs.DEVICE`, cropped to
    their ink), 10 px apart.
  - **5–8 devices:** small glyphs, 4 px apart. Eight is `MAX_DEVICES`, the ceiling of what
    is sent. If `dx` > 8, append `+N` for the rest. The approved small shapes, 2 px strokes
    like the big ones; add them to `glyphs.py` rather than hand-writing them:

    ```
    laptop 15 x 10            phone 9 x 12
    ..###########..           .#######.
    ..###########..           #########
    ..##.......##..           ##.....##
    ..##.......##..           ##.....##
    ..##.......##..           ##.....##
    ..###########..           ##.....##
    ..###########..           ##.....##
    ...............           ##.....##
    ###############           ##.....##
    ###############           ##..#..##
                              #########
                              .#######.
    ```
  - Phone icon for `dg` = `phone` / `mainphone` (this phone), laptop otherwise. The
    rejected alternative was 3 icons and `+N` at any count over 4.
  - Off: no icons, `nothing online` centred at y = 144.
- The `START: …` line goes; the sub-window glyph (power, or the ON/OFF word) says it.

## Device: native `Menu2` list that takes over and hands back

The owner: "The way garmin does it in first party is that when you press down into the
list, the Menu2 list takes over and when you press up on the topmost element of the list,
it hands back control to the other screens." **If that cannot be made to work, use Device
"A"** (`watch-pages-first.png`: device glyph at 2x, full name, role, position pips).

Design as found in the API docs (not yet tried on a device):

- The N device pages go. Pressing DOWN on Connection `switchToView`s a `WatchUi.Menu2`
  (title `DEVICES`) with `SLIDE_UP` and `:focus` 0. Arriving from the page after it (UP),
  `:focus` is the last item.
- Items: `IconMenuItem`, label = `dn[i]`, sublabel = `dr[i]` (`joined` /
  `switched data on`). The IP is only sent as `dip` when `ctl`, so do not plan on showing it.
- **Sub-window:** on devices with a subscreen, Menu2 draws the focused item's icon there
  ([Menu2 docs](https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi/Menu2.html),
  `:icon`: "The icon to display in the subscreen area when the focused MenuItem does not
  have an icon"). Icon per item: `Glyphs.DISCONNECT` when `dip[i]` is set (START takes it
  off), else by `dg` as today (`MAIN` / `MAIN_PHONE` / `PHONE` / `DEVICE`). Generate these
  as **bitmap resources** (PNG from `glyphs.py`, black on white, 31 x 31) rather than a
  custom `Drawable`: bitmaps are what the docs show for the subscreen.
- **Hand-back:** `Menu2InputDelegate.onWrap(key)` (API 3.0.0) is called on button
  products when the user tries to move off either end
  ([delegate docs](https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi/Menu2InputDelegate.html)).
  `KEY_UP` at the top: `Pages.turn(-1)` (slide down to Connection), return `false`.
  `KEY_DOWN` at the bottom: `Pages.turn(+1)`, return `false`. `Pages` needs to know the
  Devices page is a menu, not a `QuotaView`: `Pages.top` and `QuotaDelegate.onSelect`'s
  `view != Pages.top` guard assume a `QuotaView`.
- `onSelect(item)`: take that device off, under exactly today's rules: only when `ctl` and
  `dip[i]` is set, with the phone re-checking against the portal. Otherwise do nothing.
  `onBack`: what BACK does on the other pages.
- Zero devices or no session: no menu. Keep a plain page saying so, as today's `?` / `0`
  cases do.
- Risks to check on the watch: the AMOLED Instinct 3s may also be touch, and `onWrap` is
  documented for button products. If it does not fire there, BACK still leaves the list.
  Forum reports of Menu2/CustomMenu bugs were about `CustomMenu`; use plain `Menu2`.
- The AMOLEDs have no sub-window. They keep `PageDraw.indicator`, and the list shows
  without a subscreen icon.

## Running the simulator in a cloud session

Done: `garmin/tools/sim/README.md`. The readings come from `garmin/source/Fixture.mc`, a
`(:debug)` module that no `-r` build carries; hold UP (MENU) for the next.

## Checks before pushing

`make test` (579 passed after the merge), `make lint`, `python3 garmin/tools/glyphs.py`
regenerating `Glyphs.mc` with no diff beyond the intended one. A `free` key means the
Kotlin suite too (`make android-test`, which CI runs). CI compiles the watch app on push
to the branch (`garmin prg`), which is the only compile there is without the simulator.
