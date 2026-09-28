# Tegenwind style guide

How the app looks and reads. Follow it for every visual change: a new screen, a card, a colour, a
chart, a button, a line of text. If a change needs something this guide doesn't cover, add the rule
here in the same change, so the next screen can follow it too.

The shared building blocks live in `app/src/main/java/com/tegenwind/app/ui/Components.kt`
(`StatTile`, `ChartCard`, `ActionCard`, `Picker`, `rememberConfirmTap`) and `ui/TimeSeriesChart.kt`.
Use them; don't write a local copy.

## 1. Principles

- **Read at a glance, at arm's length.** The ride screen is read on a handlebar mount while riding.
  The biggest thing on it is the arrival time. Everything else is smaller and calmer.
- **Nothing that is the same every time.** A card that always says the same sentence doesn't earn
  its space, least of all on the ride screen.
- **Say what a number is.** Every number has a label or a unit. Every chart says what its axes are.
- **One meaning per colour.** See section 2. If a colour would mean something else here than on
  another screen, pick a different one.
- **Dark only.** High contrast on the handlebar and easy on the battery. There is no light theme yet.

## 2. Colour

All colours live in `ui/theme/Color.kt`. Screens use `MaterialTheme.colorScheme` or the named colours
there. Don't use a hex value in a screen.

| Name | Hex | Use |
|---|---|---|
| `Asphalt` | `#0D1113` | Page background |
| `Surface1` | `#151B1E` | Cards (`surfaceContainer`) |
| `Surface2` | `#1D2528` | Things raised above a card: menus, the Stop button at rest (`surfaceVariant`) |
| `Line` | `#2A3438` | Outlines, dividers, chart grid |
| `Ink` | `#EDF2F0` | Text |
| `InkMuted` | `#8E9A9E` | Labels, descriptions, axis text (`onSurfaceVariant`) |
| `Amber` | `#F2B53A` | The accent (`primary`, `secondary`) |
| `AmberInk` | `#241800` | Text on amber |
| `AmberDim` | `#3B2F17` | Behind the selected tab (`secondaryContainer`) |
| `SpeedColor` | `#3FB6FF` | Speed |
| `HeartColor` | `#FF5B4F` | Heart rate |
| `GoodColor` | `#48D695` | Good |
| `Danger` | `#FF6A5C` | Bad or dangerous (`error`) |

**What each colour means, everywhere:**
- **Amber** is the accent:
  - the main action and button text
  - the selected tab
  - the wind arrow
  - what the model expects (the expected-speed line)
  - traffic lights
- **Blue** is speed, and nothing else.
- **Green** is good: faster than expected, a tailwind, the fastest ride, "Recording".
- **Red** is bad or dangerous:
  - slower than expected, a headwind
  - off route, the slowest ride
  - delete, and Stop once armed

  Heart rate uses its own red as a data series.
- **Grey** is descriptive and neutral: how open a stretch of road is, your position marker, numbers
  measured against bare physics (where "slower" is normal, so red would say nothing).

Two scales on the ride screen's route bar, only there:
- **Wind:** red (`#FF3B30`) through yellow (`#FFD60A`) to green (`#30D158`), for how much the wind
  costs or gains.
- **Sky:** yellow (`#FFD233`) through white to blue (`#2F6FFF`), for sun, cloud and rain.

## 3. Type

Material 3's default scale, used like this:

| Style | Use |
|---|---|
| 48 sp bold, tabular (`etaNumber`) | The arrival time: the ride screen and "Leave now, arrive at" |
| `headlineLarge` bold | The app name on the start screen |
| `headlineMedium` semibold | Titles of the tabs' own screens (Rides, Routes, Stats) and of a route's page |
| `headlineSmall` semibold | Title of a ride's page; live numbers on the ride screen (speed, distance) |
| `titleMedium` semibold | Card titles, list-row titles, stat values |
| `bodyMedium` | Secondary lines in list rows (date, distance) |
| `bodySmall`, muted | Descriptions and explanations |
| `labelMedium`, muted | The label above a value |
| `labelSmall`, muted | A chart's subtitle |
| 10 sp, muted | Chart axis labels, legend, chart chips |

- Any number that changes or sits in a column uses tabular figures: `fontFeatureSettings = "tnum"`.
- Text never gets cut off or pushed off screen. Test at 360 dp wide (see section 8).

## 4. Layout and spacing

- **Screens:** 14 dp side padding, 10 dp between cards. On the live ride screen it's 8 dp, to fit.
  The start screen uses 24 dp padding, centred, and scrolls when it doesn't fit.
- **Cards:** Material `Card` (surface `Surface1`, 12 dp corners), 12–16 dp inside padding. Don't put
  a `Card` inside a `Card`: the inner one has the same colour and disappears. Numbers grouped inside
  a card are a plain label over a value.
- **Detail screens** start with a text link back ("‹ Rides"), then the title, then a muted line
  under it (date, length).
- **The order on a detail screen:** numbers (stat tiles), then charts, then explanations (like
  "What this ride taught"), then the action table, then any small print.
- **Tiles:** `StatTile` in rows of two or three, equal widths (`Modifier.weight(1f)`). Keep labels
  short enough to stay on one line at 360 dp.
- **Touch targets:** at least 48 dp high. Buttons in the action table are 128 × 52 dp.

## 5. Buttons and actions

One scheme:

| Kind | Looks like | Use |
|---|---|---|
| Main action | Filled amber, full width | The one thing a screen is for: Start ride |
| Action table | `ActionCard`: equal outlined buttons on the left, what they do on the right | Everything you can do with a ride or a route |
| In-place | Outlined | Small actions inside a card (Refresh, Allow access, Import GPX) and pickers |
| Text | Amber text | Navigation ("‹ Routes") and optional extras ("Try a simulated ride", "Rename", "Show all") |
| Ride controls | Full width, 56 dp | Stop ride (grey at rest, red once armed) |

- **Deleting and stopping take two taps.** The first tap arms the button: it turns filled red and
  says "Sure? Tap again" (or "Tap again to stop"). The second tap within 3 seconds acts; otherwise it
  goes back. Use `rememberConfirmTap()`. Don't open a dialog for this.
- **Dialogs** are for typing something (a name), or for a change whose consequences need explaining
  first (Update route from this ride).
- **Names say what happens:** "Rename" renames. "Change route" changes which route a ride belongs
  to. Never use a bare "Edit".
- **Choosing one of a list** uses `Picker`: a button showing the current choice with a chevron,
  opening a menu as wide as the button, with a tick at the current choice.

## 6. Charts

- **Measurements:** raw values as faint dots (the series colour at 42%), and a 2-minute rolling
  median as a 2.5 dp line on top.
- **What the model expects** is amber, 4 dp, at 85%, drawn **under** the measurements. The
  measurements always stay on top. Each segment is a line of its own: the speed the ETA uses
  there, shaped within the segment by the route's learned speed profile (slower up a bridge, faster
  down). Nothing joins one segment's line to the next: never draw a slope between two segments.
- **Axes:**
  - labels at 10 sp, muted
  - grid lines 1 px in `Line`
  - ticks on round numbers (1, 2 or 5 × a power of ten), never two ticks with the same label
  - time labels carry their unit: "−2 min", "−1 min", "now"
- **Say what the axes are,** in the chart's subtitle when the labels alone don't ("average headwind
  in km/h, − is tailwind · minutes").
- **Legend:** a small chip in the plot's top-left corner (`legend = true`). Tapping it folds it to a
  "?" and back. Use it instead of a caption that describes the lines.
- **Annotations,** like "last 2 min", go on the same kind of chip in the top-right corner.
- **A card around a chart** is `ChartCard(title, subtitle)`.
- **Bars along a route** (exposure, sky, wind) are drawn left to right from start to finish. When
  there's room, a km axis runs underneath.

## 7. Words

- **Sentence case** everywhere ("Share live", not "Share Live"). No capitals for emphasis. The one
  exception is the YES / NO of the almost-there prompt, which is read while riding.
- **Units:** km, km/h, min, bpm, %, Bft. Times are m:ss or h:mm:ss; clock times HH:mm.
- **Separators:** a middle dot with spaces between items: "13.6 km · 6 rides · usually 37:43".
  - Keep each item on one line: join its words with a non-breaking space, so a line can only break
    between items.
  - Use an en dash for ranges ("0.8–1.0 km") and a real minus (−, not -) for negative numbers
    on axes and time offsets ("−2 min").
- **Tone:** plain and short. Explain what something does and why, in the rider's terms ("wind costs
  1:40"), not the model's.

## 8. Checking a visual change

1. Build, install, and look at it on the phone (360 × 780 dp): every screen you touched, scrolled
   to the bottom, and in each state it can be in (for example empty, loading, a simulated ride).
2. Nothing is cut off or hidden under the tab bar. `uiautomator dump` shows each element's bounds.
3. On the ride screen, everything down to Stop fits without scrolling (pause and arrival bars
   aside).
4. Take before and after screenshots of what changed.
