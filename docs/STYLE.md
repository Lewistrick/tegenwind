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
| `ScaleWorse`, `ScaleEven`, `ScaleBetter` | `#FF3B30`, `#FFD60A`, `#30D158` | The red–yellow–green scale (`scaleColor`), see below |

**What each colour means, everywhere:**
- **Amber** is the accent:
  - the main action and button text
  - the selected tab
  - the wind arrow
  - what the model expects (the expected-speed line)
  - traffic lights
- **Blue** is speed, and nothing else, except the arrival-time chart, and your arrow and the
  checkpoint flags on the ride screen's map.
- **Scales on maps** are listed in section 7.
- **Green** is good: faster than expected, a tailwind, the fastest ride, a ride in the top x% of its
  route, "Recording".
- **Red** is bad or dangerous:
  - slower than expected, a headwind
  - off route, the slowest ride, a ride in the bottom x% of its route
  - delete, and Stop once armed

  Heart rate uses its own red as a data series.
- **Grey** is descriptive and neutral: how open a stretch of road is, your position marker, the
  slowest segments on Stats (measured against bare physics, where "slower" is normal).

Scales, each only where it's listed:
- **Red–yellow–green** (`scaleColor`): yellow is even, green gains, red costs.
  - Wind, on the ride screen's route bar: how much the wind costs or gains, fully red or green
    from a fifth of the time.
  - Learned time, in the segments table on a route's page: how much longer or shorter than your
    usual pace a segment takes, fully red or green from a quarter.
- **Sky,** on the ride screen's route bar: yellow (`#FFD233`) through white to blue (`#2F6FFF`), for
  sun, cloud and rain.
  - Rain in the forecast is solid blue, and it has to show: none below 0.1 mm/h, then at once 35%
    blue, fully blue from 2 mm/h.
  - A chance of rain where none is forecast shows as diagonal blue stripes over the sky colour: none
    below 20%, then from 25% to 70% opacity. Solid blue says "it will rain", stripes "it could".

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
- Text never gets cut off or pushed off screen. Test at 360 dp wide (see section 9).

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
  - A tile can carry a one-line `note` under its value, in the label's size: how the value ranks,
    such as "top 10%" in green, "bottom 20%" in red or "middle" in grey.
  - A row with a note keeps its tiles the same height: `Modifier.height(IntrinsicSize.Min)` on the
    row, `fillMaxHeight()` on each tile.
- **Touch targets:** at least 48 dp high. Buttons in the action table are 128 × 52 dp.

## 5. Buttons and actions

One scheme:

| Kind | Looks like | Use |
|---|---|---|
| Main action | Filled amber, full width | The one thing a screen is for: Start ride |
| Action table | `ActionCard`: equal outlined buttons on the left, what they do on the right | Everything you can do with a ride or a route |
| In-place | Outlined | Small actions inside a card (Refresh, Allow access, Import GPX) and pickers |
| Text | Amber text | Navigation ("‹ Routes") and optional extras ("Try a simulated ride", "Rename", "Show all") |
| Ride controls | Full width, 56 dp | Stop ride (grey at rest, red once armed), at the bottom of the ride screen, behind the scroll |

- **Deleting and stopping take two taps.** The first tap arms the button: it turns filled red and
  says "Sure? Tap again" (or "Tap again to stop"). The second tap within 3 seconds acts; otherwise it
  goes back. Use `rememberConfirmTap()`. Don't open a dialog for this.
- **Dialogs** are for typing something (a name), or for a change whose consequences need explaining
  first (Update route from this ride).
- **Names say what happens:** "Rename" renames. "Change route" changes which route a ride belongs
  to. Never use a bare "Edit".
- **A warning before you act** (the chosen route doesn't fit where you are or the hour) is one
  line of `bodySmall` in red under the control it is about, saying what is off in plain words. When
  there's an obvious fix, a text button under it does it ("Switch to werk-woon"). No icon, no card,
  no dialog; nothing at all when everything fits.
- **Choosing one of a list** uses `Picker`: a button showing the current choice with a chevron,
  opening a menu as wide as the button, with a tick at the current choice.

- **Settings** ("Rider and bike") are a detail screen like any other: "‹ Ride", the title, a muted
  line, then a card per thing being set.
  - Numbers are `OutlinedTextField`s with their unit as a suffix ("84 kg"). What reads as a number
    is kept at once; there is no Save button.
  - Choices are `Picker`s under a small label. A muted line under each says what the current
    choice means ("Forearms or hands forward, back bent.").
  - Fields that only matter for one choice (a motor's power and cut-off speed) only show with it.
  - What you can do with the thing (ride this bike, delete it) goes in an `ActionCard` under its
    card, deleting with two taps as everywhere.

## 6. Charts

- **Measurements:** raw values as solid dots in the series colour, and a 2-minute centred rolling
  average (each point averages the minute either side) as a 2.5 dp line on top. On a ride still
  going, the last minute has no average yet.
- **Segment borders** on the live ride are vertical grid lines, in the grid's colour.
- **What the model expects** is amber, 4 dp, at 50%, drawn **under** the measurements. The
  measurements always stay on top. Each segment is a line of its own: the speed the ETA uses
  there, shaped within the segment by the route's learned speed profile (slower up a bridge, faster
  down). Nothing joins one segment's line to the next: never draw a slope between two segments.
- **A value with a band** (the ride screen's "Arrival time"): the value as a 3 dp line, the band's
  edges as 1 dp lines in the same colour, and the area between them filled with that colour at 18%.
  Arrival time (HH:mm, ticks on whole minutes) runs upward, minutes since the start across.
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
- **Charts stacked on one time** share its axis (`TimeAxis`): on the ride screen the speed chart and
  the arrival-time chart show the same stretch of the ride, laid out the same way and with the same
  room for value labels (`CHART_PAD_LEFT`), so a moment sits above the same spot on both. Tapping
  either switches both, between the last 2 minutes and the whole ride, and each says which with the
  same chip in its top-right corner.
- **The arrival chart's clock axis** fits what is shown, in round steps from 5 seconds up to hours.
  Ticks a minute or more apart are clock times with the hour ("07:45"); under a minute apart they are
  minutes and seconds without the hour ("59:30", "00:00", "00:15"), and "min:s" in the corner says so.
- **Bars along a route** (exposure, sky, wind) are drawn left to right from start to finish. When
  there's room, a km axis runs underneath.

## 7. Maps

`RouteMap` (`ui/RouteMap.kt`) draws a route as a line on the card: north up, one scale both ways,
no streets, no labels. For a road you ride every day the shape is enough.

- **The road** is 5 dp, in the outline grey where there is nothing to say about a stretch.
- **One thing at a time** colours the stretches, chosen with a `Picker` above the map when there is
  a choice. The card's subtitle says what the colours mean.
  - Time gained or lost against what was expected (a ride), learned pace (a route) and slope use
    the red–yellow–green scale: green gains, red costs, fully from a quarter (time) or 2% (slope).
  - Standing still runs from yellow to red by seconds, fully at 10 s; nothing where you never stand.
  - Wind exposure is descriptive, so grey: bright is open, dim is sheltered.
- **A ride's own track** is a thin light line over the road.
- **Dots:** amber for a spot that matters (where you stood still, a light that stops you), grey for
  one that doesn't (a light that never stops you). The ends of the route are small grey dots.
- **You** are a solid light dot on a route's or ride's map. **On the ride screen** the map turns so
  the way you're going points up and zooms to about the next 45 s at your speed (150–700 m ahead,
  wider to keep a nearby ghost in view); turning and zooming glide. You are a blue arrow at 92%
  with a dark rim, two-thirds of the way down; the ghost (an earlier ride, where it was this long
  after setting off) is a grey arrow at 35%. An arrow is a triangle with a notch cut out of its
  back, pointing the way of travel. Segment borders are small blue checkpoint flags (a pole with a
  pennant, upright on screen): bright ahead, at 35% once passed, since a stretch only takes its
  colour when you reach its end.
- **Tapping a stretch** gives it a light edge and says what is known about it in one muted line
  under the map. That line keeps two lines of room, so the page doesn't jump.
- The ride screen's map is 1.25 times as wide as high. Other maps are as wide as the card and as tall as the route needs (between 1.3 and 2.8 times as wide as high).

## 8. Words

- **Sentence case** everywhere ("Share live", not "Share Live"). No capitals for emphasis. The one
  exception is the YES / NO of the almost-there prompt, which is read while riding.
- **Units:** km, km/h, min, bpm, %, Bft, W, load/day (training load per day: an hour of solid
  riding is about 100). Times are m:ss or h:mm:ss; clock times HH:mm.
  - Power is an estimate from speed, never a measurement: it always carries "≈" and is rounded to
    5 W ("≈125 W"). It's how form is shown; the word "form" is not.
  - A unit that is a word, like load/day, follows the value in the label's size, muted and not
    bold, so the value stays on one line: "42 load/day".
- **Separators:** a middle dot with spaces between items: "13.6 km · 6 rides · usually 37:43".
  - Keep each item on one line: join its words with a non-breaking space, so a line can only break
    between items.
  - Use an en dash for ranges ("0.8–1.0 km") and a real minus (−, not -) for negative numbers
    on axes and time offsets ("−2 min").
- **Tone:** plain and short. Explain what something does and why, in the rider's terms ("wind costs
  1:40"), not the model's.

## 9. Checking a visual change

1. Build, install, and look at it on the phone (360 × 780 dp): every screen you touched, scrolled
   to the bottom, and in each state it can be in (for example empty, loading, a simulated ride).
2. Nothing is cut off or hidden under the tab bar. `uiautomator dump` shows each element's bounds.
3. On the ride screen, the arrival card and the speed chart fit without scrolling (pause and
   arrival bars aside). The arrival-time chart and Stop come after them, behind the scroll.
4. Take before and after screenshots of what changed.
