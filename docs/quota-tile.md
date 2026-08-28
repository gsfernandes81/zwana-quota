# The quota in the Quick Settings panel

The data figure the [home-screen widget](../quota_widget.py) draws, on a
Tasker Quick Settings tile — one swipe down from anywhere, including the lock
screen, without leaving whatever you were doing.

It is the same reading and the same cache as the widget and the status line, so
the tile and the home screen never disagree. **Nothing here costs quota**:
`ic.zwana.io` is the vessel's own captive portal, not something across the
phone's radio.

The tile says:

```
   1.68 GiB
   95%, +762 MiB 05:30
```

The label is what is left. The subtitle is that as a share of today's pool, then
tonight's top-up and the local time it lands. When the reading is old or the
portal is unreachable the subtitle says **that** instead of the share —
`2h ago, 05:30`, `offline, 05:30` — because a figure the tile cannot stand
behind must not be shown as if it can. When there is no reading at all the tile
reads `quota ? / no reading`.

## What you need

| | |
|---|---|
| **Tasker** | Android 7+ |
| **AutoNotification** | the tile itself. Tasker's own tiles would do, but there are three of them and they are wanted elsewhere; AutoNotification has forty, and a subtext line, which is where half of what this tile says lives |
| **Termux:Tasker** | the plugin, from the same source as Termux (F-Droid *or* Play, never mixed — different signatures and the plugin will not connect) |
| `allow-external-apps = true` | in `~/.termux/termux.properties`, then `termux-reload-settings` |

Without the last one the plugin refuses to run anything, and it refuses quietly
— the tile simply never changes.

## The Termux half

```bash
tasker/install.sh
```

That symlinks `termux/tasker/zwana-tile` into `~/.termux/tasker/`, which is the
only directory Termux:Tasker will run anything from, checks the property above,
and runs the script once so you can see its four lines. A symlink rather than a
copy, so a `git pull` is the whole update. If Tasker's file picker will not show
a symlink, `tasker/install.sh --stub` puts a two-line script there that
calls the checkout instead; it updates the same way.

The script takes a mode and, if Tasker's splitter is being difficult, a
separator:

```bash
zwana-tile                   # read the portal, fall back to the cache — tap and profile
zwana-tile cached            # answer from disk at once, refresh in the background
zwana-tile open              # open the portal in a browser — the long press
zwana-tile --sep '|'         # the same four fields on one line, joined by '|'
zwana-tile --qs-size large   # widths for the tile size you dragged out
```

and always prints exactly four lines, whatever happens:

| line | is | for |
|---|---|---|
| `%stdout1` | `1.68 GiB` | the tile's label |
| `%stdout2` | `95%, +762 MiB 05:30` | the tile's subtitle, which AutoNotification calls the subtext |
| `%stdout3` | `active` / `inactive` | `active` while free data is left, `inactive` once the figure is only paid data. Never a third, greyed-out state — see below |
| `%stdout4` | `ok` / `low` / `critical` / `unknown` | pick an icon with it |

Four lines always, and exit 0 always. Both are for the same reason: Tasker
addresses these by position, so a short answer would leave the *previous* run's
label standing beside this run's subtitle, and a non-zero exit would abort the
task and leave the tile saying whatever it said an hour ago. A failure is
reported on the face, where it can be seen; `%stderr` still carries the detail.

## The Tasker half

Both of Tasker's pickers have a search box and the exact wording moves between
versions, so search for **AutoNotification** and for **command** rather than
following a menu path. Field names below are described by what they do.

**Task: `Zwana Tile`**

1. **Plugin → Termux:Tasker.** Configure it with executable `zwana-tile` and
   argument `cached` or nothing (below). Leave it running in the background
   rather than in a terminal session — a terminal session shows the output in
   Termux and returns nothing to Tasker. Give the action a timeout of about 30
   seconds; the script's own is 20.
2. **Variables → Variable Split.** Name `%stdout`, splitter `|`, and give the
   action above the argument `--sep '|'` so that is what it gets. This is what
   makes `%stdout1`–`%stdout4`.

   Splitting on `\n` instead is the obvious thing and does not reliably work:
   Tasker takes the splitter as a **literal string**, so `\n` is a backslash and
   an n, matches nothing, and leaves the whole answer in `%stdout1` — a tile
   reading `1.68 GiB 95%, +762 MiB 05:30 active ok`, with no error anywhere to
   say why. Handing it a separator it cannot misread is cheaper than arguing
   with the field. See *If the splitter does nothing* below for the two ways to
   keep the newline if you would rather.
3. **Plugin → AutoNotification → Tiles.** Pick a tile number (1 of the 40),
   then:

   | field | set to |
   |---|---|
   | label / text | `%stdout1` |
   | subtext | `%stdout2` |
   | state / status | `%stdout3` |
   | command | `zwanatile` — anything, as long as the profile below matches it |
   | icon | one icon, or see below |

   Two of those need a word. **State** may be a dropdown rather than a field
   that takes a variable; if it is, leave it on active and either ignore
   `%stdout3` or set the tile twice under an **If**. And whatever it is, do not
   wire it to a third *unavailable* state — that greys the tile out and stops it
   being tapped, and a tap is how a tile with no reading gets one. The script
   never emits that word for the same reason.

   **Icon**: either set one and ignore `%stdout4`, or add an **If %stdout4 ~
   ok/low/critical/unknown** around one tile action per icon. The icon is the
   only part of the tile that no amount of text budget buys back, which is why
   the fourth line exists at all.

**The tile itself** — pull the panel down, edit the tiles, and drag in
AutoNotification's tile with the number you chose. It stays blank until the task
has run once, so run the task by hand first.

**Profile: tapped.** Event → **AutoNotification** (or AutoApps → Command, if you
have the subscription), matching the command you set above — `zwanatile`, which
arrives in `%ancomm`. Point it at `Zwana Tile`.

**The tap's arguments are the same as the profile's** — the plain live read:

```
--sep | --qs-size medium
```

No quotes around the `|`: Termux:Tasker hands the arguments to the script
directly, with no shell in between to strip them, so a quoted `'|'` arrives as
three characters and is refused. And **no `cached`**: a tap means *tell me now*,
and it is the one mode that deliberately does not go to the portal.

Two things you can do with `cached` if you want the tile to answer in the
instant the panel opens rather than a second later. Either put a second
Termux:Tasker action (argument `cached`), Variable Split and Tiles action
*before* the live one, so the tile answers at once from the last reading and
again with a fresh figure a second later — or use `cached` alone and accept
being one tap behind, which now converges rather than sticking, because a
cached answer older than 45 seconds starts a refresh in the background on its
way out.

**Profile: kept fresh.** Time → every 30 minutes → task `Zwana Tile` with no
argument (the blocking read). Free, as above. A tile is only redrawn when
something runs, so without this it holds whatever it last said; and if the
panel is already open when it fires, the new text may not appear until you
close and reopen it.

Tasker's own three tiles will do all of this too — same four lines, same task,
its **Quick Settings Tile** action instead of AutoNotification's and its
tile-click event instead of the command. Nothing on the Termux side knows the
difference.

Nothing else has to be arranged around midnight UTC: the reset is in the
subtitle, and the figure the tile shows after it is the new day's.

## If a tap does not change the number

In the order they actually happen:

1. **The action says `cached`.** That mode does not go to the portal — it
   answers from disk and starts a background refresh, so the figure moves on
   the *next* tap and never on this one. The tap wants no mode at all.
2. **The panel needs reopening.** A tile is redrawn when the panel next reads
   it, so an update that arrives while the shade is open may not show until you
   close and reopen it. Test with the panel closed: tap, swipe away, swipe back.
3. **The action timed out.** The live read is bounded at 20 seconds and the
   Termux:Tasker action's own timeout must be longer — 30 is the suggestion
   above. A shorter one kills the read and the tile keeps its old text.
4. **Nothing ran.** Check Tasker's run log, and `%stdout` in the variable list.
   If `%stdout` holds a current-looking four-field answer, the fault is
   downstream — the split or the Tiles action, not the script.

The script itself is testable without any of that:

```bash
zwana-tile            # should take a second or two and print a fresh figure
zwana-tile cached     # should return instantly
```

If the first one is instant too, it is answering from the cache because the
portal did not reply.

## If the splitter does nothing

The symptom is a tile with everything on one line, or `%stdout2` empty and the
subtext blank. Check `%stdout1` in Tasker's variable list: if it holds the whole
four-field answer, the split matched nothing.

In order of preference:

1. **Give the script a separator.** `--sep '|'` on the Termux:Tasker action,
   `|` in the Variable Split. One character only, and the script refuses a
   separator that appears in the reading itself rather than quietly producing
   the wrong number of fields.
2. **Convert the newlines first.** Variable Search Replace on `%stdout`, search
   `\n`, **regex on**, replace with `|`, then split on `|`. Search Replace does
   treat `\n` as a newline, which is the inconsistency that makes this whole
   section necessary.
3. **Put a real newline in the splitter field.** Some Tasker versions accept one
   pasted in from elsewhere; the field will look empty afterwards, which is
   indistinguishable from an empty field that does nothing. Not recommended for
   that reason alone.

The script's own answer is always four lines whichever you pick: the join is
done after the four-line check, so an answer that was malformed cannot be made
to look well-formed by having been joined.

## Opening the portal

```bash
zwana-tile open
```

Opens `https://ic.zwana.io` in the browser and prints the address it opened.
The address is asked of `zwana_quota` rather than typed in, so this repo spells
it once — and the copy it derives from is the one every quota reading already
exercises. Free, like everything else pointed at the portal. It uses
`termux-open-url` if the Termux:API package is installed and `am start`
otherwise; `open` is the one mode that does not print a tile, so don't wire a
Variable Split behind it.

**A long press is not ours to bind.** Android hands a tile's long press to the
app that owns the tile — App Info, or that app's own tile-preferences screen if
it declares one. Tasker and AutoNotification own these tiles, not us, so unless
AutoNotification's Tiles action offers a long-click command field of its own,
there is nothing to attach to. Look for one; if it is there, give it the command
`zwanaportal` and add:

**Profile: portal.** Event → AutoNotification, command `zwanaportal` → task with
one action: Plugin → Termux:Tasker, executable `zwana-tile`, argument `open`.
(Or skip Termux entirely with Tasker's **Net → Browse URL**, `https://ic.zwana.io`
— at the cost of a second copy of the address, which is the thing this repo
keeps down to one.)

**If there is no long-click field**, use a double tap instead, which the tap
profile can detect by itself. In the `Zwana Tile` task, before anything else:

1. **If** `%TIMES` − `%zwana_tapped` < 2 → Termux:Tasker `zwana-tile open`,
   then **Stop**.
2. **Variable Set** `%zwana_tapped` to `%TIMES`.

`%TIMES` is Tasker's seconds-since-epoch, so a second tap inside two seconds
opens the portal and a single tap goes on refreshing the tile as before.

## Samsung's resizable tiles

One UI 8.5 lets a tile be dragged to a different size in the panel. **Nothing
tells the app which size was chosen** — there is no callback, no configuration
change, nothing to read back. So the size is a setting on this end too:

```bash
zwana-tile --qs-size small        # 2 GiB
zwana-tile --qs-size medium       # 1.68 GiB / 95%, reset 05:30   (the default)
zwana-tile --qs-size large        # 1.68 GiB / 95% of 1.63 GiB, +762 MiB 05:30
```

| size | label | subtitle | for |
|---|---|---|---|
| `small` | 5 chars | **none** | a one-cell tile, where the subtext is not drawn at all |
| `medium` | 10 | 16 | the ordinary tile — what the doc above assumes |
| `large` | 12 | 34 | a tile dragged wide, which gets told the pool as well |

The ladder is climbed as well as descended: `large` doesn't just fail to clip,
it spells out what `medium` had to drop. A wide tile saying exactly what a
narrow one says is the same waste as a narrow one clipped, only quieter.

**`small` has no subtitle, so it cannot carry the reset time.** That is a real
loss and not a tidier layout — it is the one fact on the tile that can't be
worked out from the rest. If you run a small tile, wire the icon to `%stdout4`
so the tile still says *how bad it is* when it can no longer say *when it
changes*; the icon is the only thing left at that size. With AutoNotification's
forty tiles you can also just have both: a small one for the glance and a large
one further down the panel, two Tiles actions in the same task.

## Measuring your own tile

Presets are a starting point. The real widths depend on the size you dragged,
the panel's column count, the font scale and — because the font is proportional
— on whether the text is digits or letters. So measure rather than guess:

```bash
zwana-tile cached --qs-probe --qs-size large
```

That puts a ruler on the tile instead of the quota, deliberately longer than
any tile can show. Read off the last mark still visible, subtract a character
or two for letters being wider than the ruler's dashes, and set it:

```bash
zwana-tile --qs-width 12 30
```

`--qs-width` overrides `--qs-size`, so a measured tile always beats a named one.
Then run

```bash
python3 ~/zwana-quota/quota_widget.py --self-test
```

which checks every figure the tile can ever draw against whatever you set — that
the label fits, that the subtitle fits, that the reset survived the squeeze, and
that a stale or offline reading still says so at every size. A clipped subtitle
is usually the reset that got clipped, and Android clips without a word.

If you settle on widths you want as the default, they are `QS_LABEL`,
`QS_STATUS` and `QS_SIZES` at the top of the Quick Settings section in
`quota_widget.py`.
