# Bike Dashboard: plan

## Context

Your current tracking apps don't give you what you want on the handlebar. The goal is a personal Android app for commuting, shown on a phone mounted on the handlebar, with:

1. **Heart rate over time**: raw samples as a scatter plot plus a 2-minute rolling average line. The source is a Withings Steel HR.
2. **Speed over time**: from phone GPS, also scatter plus a 2-minute rolling average.
3. **Smart ETA** on saved routes ("home → work"). It accounts for position on the route, current pace, wind (open fields vs city), inclines and traffic, and it learns from every ride: which segments are slow or fast, fatigue during a ride, fitness over months.
4. **Route stats page**: fastest, slowest and average time per route, plus trends.

> **Plan versus project.** Sections 1–8 are the plan as it was written before building started, and
> they are kept that way on purpose. The project drifted from it: some parts were simplified or
> dropped, and features came in that the plan never mentioned. **Section 9 describes what was
> actually built** and where it differs. The status notes in section 7 are kept up to date.

---

## 1. Two facts that shape the design

| Fact | Consequence |
|---|---|
| The Google Fit APIs are **supported only until the end of 2026**, and no new developer sign-ups have been allowed since May 2024. Google's migration guide points Android apps to **Health Connect**. | The app is built on Health Connect, not Google Fit. |
| The guide says **Health Connect and the Google Health API do not provide live sensor data**. Live data has to come from FusedLocationProvider (GPS) or directly from Bluetooth. The **Steel HR does not broadcast BLE heart rate**. Live HR only shows inside the Withings app during a workout. The Google Health API covers Fitbit/Google devices, not Withings. | With your current watch, HR can only be **near-live**. See the recommendation below. |

### Phase 0 result (11 Sep 2026): no live HR from the Steel HR

Measured with the heart-rate delay test on a Galaxy SM-S942B (Android 16):
- The Withings app writes the watch's **regular readings** (about one every 10 min) to Health Connect promptly. Their timestamps can be a few seconds *after* the time they were stored, so either the watch clock runs slightly ahead or Withings stamps readings at sync time.
- **Workout heart rate is only written when the workout ends.** Forcing a sync during a workout (opening the Withings app) sends nothing new. Ending the workout sends all readings at once.
- Consequence: during a ride, heart rate from the Steel HR can only be shown **after the ride**. Live heart rate on the ride screen needs a Bluetooth heart-rate sensor (the `BleHrSource` below). Post-ride backfill from Health Connect works and stays in the plan: the Stats page and the fitness model use it.

### Original heart-rate setup (before the test)

Path: **Withings app → Health Connect → our app**, with no extra hardware.

- **On the watch:** start a cycling workout when you set off. This switches the watch to continuous HR instead of roughly one sample every 10 minutes.
- **In the app:** while riding, poll Health Connect about every 30 s using the **Changes API** (a change token, so only new records come back).
- **Live HR tile:** shows the latest bpm plus a freshness badge (for example "♥ 142 · 3 min ago"). The badge turns grey if nothing new has arrived for more than 5 minutes.
- **HR chart:** points appear whenever a sync arrives. The 2-minute rolling average is computed only over real samples and is drawn with gaps where data is missing, so the chart never pretends to be live.
- **Post-ride backfill:** a WorkManager job re-reads the ride window at about +15 min and about +2 h. This completes the HR chart and recomputes ride load, which feeds the ETA model's fitness term.
- **Future option:** all HR code sits behind a `HeartRateSource` interface. A standard BLE strap (Polar H10 or Verity Sense) could be added later as a second source, giving true live HR, with no other changes.
- **Phase 0 spike (decision gate):** before building the HR feature, ride once with a one-screen test app that logs, for each HR record, its timestamp and when it arrived in Health Connect. This measures the real latency. Some users report that Withings → Health Connect heart-rate sync is unreliable, so this is worth knowing on day one.

---

## 2. Tool choices

**Development environment**
- **Android Studio** (latest stable) with the Gemini/Claude Code workflow; JDK 21; Gradle (Kotlin DSL) with a **version catalog** (`libs.versions.toml`).
- A physical **Android 14+** phone with USB debugging. Health Connect is built into Android 14 and later.
- **Health Connect Toolbox** (Google's test app) to insert fake HR records in the emulator.
- **git + a private GitHub repo**, with **GitHub Actions** building the APK on every push.
- Distribution: **sideloaded APK**, no Play Store needed.

**App stack: native Kotlin.** Health Connect, foreground GPS services and Bluetooth are all first-class on native Android, and cross-platform stacks would add plugin risk exactly where this app is most complex.

| Concern | Tool |
|---|---|
| Language / UI | **Kotlin 2.x** (K2), **Jetpack Compose + Material 3**, dark high-contrast theme |
| Navigation / DI | **Navigation Compose**, **Hilt** (with KSP) |
| Async / state | **Coroutines + Flow**, `StateFlow` per screen (MVVM / unidirectional data flow) |
| Persistence | **Room** (SQLite) for rides, samples, routes and model parameters; **DataStore** for settings |
| GPS | **Fused Location Provider** (Play Services), 1 Hz high accuracy, in a **foreground service** (type `location`) |
| Heart rate | **Health Connect Jetpack SDK** (`androidx.health.connect:connect-client`): `HeartRateRecord` and the Changes API. Optional later: **Kable** (Kotlin BLE) for a strap |
| Background jobs | **WorkManager**: HR backfill, route enrichment, model update after each ride |
| Charts | **Vico** (Compose charts): a line layer for the rolling average, plus a points-only layer for the raw scatter. Fallback: a small custom Compose `Canvas` chart if 1 Hz updates stutter |
| Maps | **MapLibre Android** (via `maplibre-compose`) with **OpenFreeMap** vector tiles (free, no API key) |
| Geometry | **spatial-k** (Kotlin port of Turf): distance, bearing, nearest-point-on-line, line simplification |
| Weather / wind | **Open-Meteo Forecast API** (free, no key): 15-minute wind speed, direction and gusts. In the Netherlands, `models=knmi_seamless` (KNMI HARMONIE, about 2 km resolution) |
| Elevation | **Open-Meteo Elevation API** (Copernicus DEM), queried once per route |
| Map context | **OSM Overpass API**, once per route: building density along each segment, traffic signals, crossings and road type |
| HTTP / JSON | **Ktor client + kotlinx.serialization** |
| Math | **EJML** (small linear algebra) for the Bayesian regression and Kalman filter |
| Voice (later) | Android **TextToSpeech** for optional ETA announcements |
| Testing | JUnit 5, **Turbine** (Flow), Compose UI tests, **Roborazzi** screenshot tests, emulator GPX playback |
| Model research (optional) | **Python + Jupyter** (pandas, statsmodels, scikit-learn) on exported ride CSVs, to backtest the ETA model before porting changes to Kotlin |
| Prototype | Self-contained **clickable HTML** (inline SVG charts, fake animated ride), published as a private Artifact link you can open on your phone |

---

## 3. Architecture

```
┌──────────── UI (Compose) ────────────┐
│ RideScreen  RouteMapScreen  Stats  Routes │
└──────────────▲───────────────────────┘
               │ StateFlow<RideUiState>
┌──────────────┴──────── RideService (foreground) ─────────────┐
│ LocationSource ──► SpeedPipeline ──► RollingWindow(120 s)    │
│ HeartRateSource ─► HrPipeline ────► RollingWindow(120 s)     │
│   ├ HealthConnectHrSource (poll + backfill)                  │
│   └ BleHrSource (optional later)                             │
│ RouteTracker (snap to route, progress, off-route)            │
│ WindProvider (Open-Meteo, refreshed every 10 min)            │
│ EtaEngine (physics + learned segments + rider state)         │
└──────────────┬───────────────────────────────────────────────┘
               ▼
        Room DB  ◄── WorkManager: backfill HR, update model, enrich route
```

A single Gradle `app` module with clear packages keeps things simple for a personal app: `ride/`, `sensors/`, `routes/`, `eta/`, `weather/`, `stats/`, `data/`, `ui/`.

**Room tables:** `Route`, `RouteSegment` (index, polyline, length, bearing, grade, exposure prior, signal count, and the learned params), `Ride`, `TrackPoint` (time, lat, lon, speed, accuracy), `HrSample`, `SegmentTraversal` (ride, segment, duration, moving and stopped time, headwind, avg HR), `RiderState` (fitness, fatigue history), `ModelParams`.

**Live pipeline details**
- Speed comes from `Location.speed` (Doppler, more accurate than differencing positions). Fixes with horizontal accuracy above 20 m are dropped.
- **Auto-pause:** below 1.5 km/h for more than 5 s counts as stopped. Stopped time is stored per segment, which is how the model learns traffic lights.
- `RollingWindow(120 s)`: a time-based deque, not a count-based one, so gaps in the data don't distort the average.
- Charts show the last 10 minutes; tap to toggle the full ride.
- The screen stays on while riding (`FLAG_KEEP_SCREEN_ON`). Portrait and landscape layouts are both supported.

---

## 4. Routes

- **Create:** either record a ride ("Record as route", simplified with Douglas-Peucker) or **import a GPX** from Komoot or Strava.
- **Segment:** cut the route into segments of about 200–300 m, with extra cuts at sharp grade changes and at signalised crossings.
- **Enrich (once, in the background):** fetch elevation for each segment's grade, and fetch OSM building density within 50 m to set a **wind-exposure prior** (open polder ≈ 1.0, city centre ≈ 0.3). Also count traffic signals and crossings and record the road type.
- **Track progress:** snap each GPS fix to the route polyline and track distance along the route. Progress only moves forward, with hysteresis. More than 50 m away counts as off-route, and the ETA pauses.
- **Detect the route:** auto-detect the likely route at ride start from position and time of day (for example weekday 08:00 near home → "home → work"). You can also pick it by hand.

---

## 5. The ETA model

Logistic regression predicts a **probability or class**, not a duration. The right tool is a **regression on segment time**, built in three layers. It stays useful from ride 1 and gets smarter over time.

**Layer 1: physics baseline (works from the first ride).** For each segment, solve the cycling power balance for speed *v*:

`P_rider = ½·ρ·CdA·(v + w_head)²·v + Crr·m·g·v + m·g·grade·v`

- `w_head` = wind speed at rider height × cos(wind − bearing) × **segment exposure**.
- Wind at rider height comes from Open-Meteo's 10 m wind via a log wind profile. City roughness makes that factor small; open fields make it large, which gives the "wind matters more in the polder" effect a physical basis.
- `P_rider` (your cruise power) is estimated from your past rides.

**Layer 2: learned per-segment corrections.** A Bayesian linear model on `log(actual / physics time)` per segment. Features:
- a segment offset (traffic, lights, busy crossings)
- a learned exposure multiplier (the OSM prior is only the start)
- a rush-hour flag
- the uphill/downhill residual

The model is updated online after every ride with conjugate Normal updates, which are cheap and need no retraining step. A forgetting factor of about 0.98 lets it follow seasons and road works.

**Layer 3: rider state**
- **Short-term (during a ride):** a 1-D **Kalman filter** on "today's form", a pace multiplier updated after each completed segment (actual vs predicted). A **fatigue** term lowers expected power with elapsed effort. When HR data is present, cardiac drift at constant speed strengthens this signal.
- **Long-term (across rides):** **fitness and fatigue load** using the Banister model: 42-day and 7-day moving averages of ride load. Load is TRIMP from HR when available, otherwise duration × intensity. These adjust `P_rider`.

**Output**
- ETA = now + the sum of predicted times for the remaining segments. Each segment uses the wind forecast for the time you are expected to reach it, plus current form.
- An **uncertainty band** (P10–P90) comes from the posterior variance, for example **"08:42 ± 1 min"**.
- A post-ride "what the model learned" card, for example "Segment 7 (Stationsplein) +38 s vs expected; lights".

**Cold start:** rides 1–3 use physics with default parameters plus live pace. From about ride 5 on a route, the learned corrections take over.

**Quality target, backtested:** leave-one-ride-out mean absolute error below 1 min at the halfway point of a roughly 25 min commute.

---

## 6. Screens (wireframe → prototype)

The bottom navigation has **Ride · Map · Stats · Routes**. The design is dark with big type and readable at a glance on a mount.

1. **Ride (live):** large speed number (current and 2-minute average) and HR number with a freshness badge. The ETA strip shows the arrival time ± band, a progress bar, remaining km, and a wind arrow with a head/tail/cross label. Below are two charts, HR and speed, each as a scatter plus a 2-minute rolling line.
2. **Map:** the route on the map, colored by predicted segment speed compared with your typical speed. Shows your position, a wind arrow, and the next "slow" segment ("Lights in 400 m").
3. **Stats:** route picker; KPI tiles for fastest, slowest, average and median. Also a trend chart of ride durations over time with the fastest and slowest rides highlighted, a histogram of durations, a scatter of headwind vs duration, and a table of the slowest segments.
4. **Routes:** the route list with ride count and typical time, plus "Record new", "Import GPX", "Re-enrich" and "Reset learning" actions.

The **clickable HTML prototype** is the first deliverable after approval. It lives in `prototype/index.html`, is published as a private Artifact link, and runs a fake animated ride so the live charts move.

---

## 7. Roadmap

| Phase | Deliverable | Status |
|---|---|---|
| 0 | Clickable HTML prototype; **Health Connect latency spike** with the Steel HR (decision gate) | ✅ done |
| 1 | Android project skeleton, theme, navigation; foreground `RideService`; live GPS speed with scatter and rolling chart; ride recording to Room | ✅ done |
| 2 | `HealthConnectHrSource`: permissions, including the required privacy-rationale activity; polling, freshness badge, post-ride backfill | ✅ done, reshaped by Phase 0: permissions and post-ride heart rate only. No polling or freshness badge, since the Steel HR can't deliver live data |
| 3 | Routes: record or import GPX, segmentation, elevation and OSM enrichment, map screen, route tracking | ✅ done, without a map screen: the ride screen's progress bar and the route's wind-exposure strip took its place |
| 4 | ETA v1: physics plus Open-Meteo wind plus live pace, shown on the Ride screen | ✅ done |
| 5 | Stats dashboard | ✅ done |
| 6 | ETA v2: Bayesian segment learning, Kalman form, fatigue and fitness, uncertainty band, "what I learned" card | ✅ done (segment learning is a per-segment offset, not the full multi-feature model; no fatigue within a ride — see below) |
| 7 | Optional: BLE strap source, voice announcements, CSV export plus Jupyter backtest notebook | not started |

### Status (16 Sep 2026)

- **Phase 2 loose end closed:** the original permission-request UI lived in a one-off "HR test" debug tab (`hrprobe/`), built for the Phase 0 spike. That tab and its dead code were removed; requesting Health Connect access now happens inline on the ride detail screen (an "Allow access" button when heart rate can't be read yet), so there's no dependency on a debug-only screen any more.
- **Phase 5 built:** a Stats tab with a route picker, KPI tiles (fastest/slowest/average/median duration), a duration trend chart (fastest/slowest highlighted), a duration histogram, a headwind-vs-duration scatter, and a slowest-segments table (actual vs modeled time, from `SegmentTraversalEntity`).
- **Distribution today:** there's no CI/APK pipeline yet (no `.github/workflows`) despite it being in the tool choices above. The app reaches the phone by building locally and running `./gradlew installDebug` (or Android Studio's Run button) over a USB connection to the phone — this has been the deployment path since Phase 1, not something tied to a specific phase.

### Live sharing (22 Sep 2026): the app is no longer purely local

Riding with "Share live" on, the phone posts its position, ETA, wind and per-segment corrections
every 5 seconds to **meewind**, a small FastAPI service on your own server, and the link goes out
with the Share ETA message. That link is good for one ride.

This is the first internet-facing part of the project, so the boundaries are deliberate: nothing is
sent until the ride has set off, the server keeps rides in memory only and forgets them within the
day, the token never appears in a URL path, and the feature is switched off entirely unless
`meewind.key` is set in `local.properties`. The JSON contract lives in `docs/CONTRACT.md` in the
meewind repo. It is pinned by `tests/test_contract.py` there and by `LiveContractTest.kt` here, which
runs against copies of meewind's fixtures in `app/src/test/resources/contract`.

### Phase 6 result (17 Sep 2026): the ETA learns the road and the rider

- **Layer 2 (`SegmentLearner`)**: every clean pass folds `log(actual / physics)` into that segment with the same conjugate Normal step the form filter uses, stored on `route_segments` (`learnedLogMean`, `learnedLogVar`, `learnedPasses`) and applied by `EtaModel.correctedSpeedMps`. A forgetting factor of 0.98 lets it follow road works. Simulated rides never teach it anything.
  - **Simplified against the original plan:** this is a single per-segment offset, not a multi-feature linear model. The rush-hour flag, learned exposure multiplier and uphill/downhill residual are *not* separate features. With one commute ridden a few times a day, each segment only gathers a handful of observations, so four features per segment would be badly under-determined; the offset carries almost all of the signal. Worth revisiting only if backtesting says otherwise.
- **Layer 3 long term (`Banister`)**: 42-day fitness and 7-day fatigue from each ride's load, shown on the Stats tab. Load is TRIMP once Health Connect has the ride's heart rate (upgraded when you open a ride), otherwise estimated from duration and pace. Old rides were backfilled on first launch.
  - Freshness only *modulates* the recent-form median rather than replacing it, so it cannot double-count what `priorForm` already knows, and it is clamped to ±5%.
  - Both averages start at the average day instead of zero. Starting at zero, the 42-day average spends months catching up to the 7-day one, and every new rider reads as permanently tired — a bug the unit tests caught.
- **Uncertainty band**: now adds each remaining segment's own posterior variance, treated as independent between segments (they are separate parameters), so the band tightens as the route gets learned instead of sitting at a flat 4%.
- **"What the model learned" card** on a finished ride: the three segments that differed most from the prediction, with how many passes that segment has behind it.
  - **Changed 25 Sep 2026: "What this ride taught".** Ranking by surprise kept showing the same slow segment after every ride, even once the model knew it was slow. The card now ranks segments by how far this ride moved their expected time (at least 1 s), showing expected vs actual time and the nudge. Learning now also divides out the day's form, so a tired day isn't blamed on the road. Rides from before this change were filled in by replaying history exactly.

**Project layout (as planned; section 9 has the real one)**
```
bike-dashboard/
  prototype/index.html          # Phase 0 wireframe/prototype
  android/                      # Gradle project (settings.gradle.kts, gradle/libs.versions.toml)
    app/src/main/java/.../ride/RideService.kt
    app/src/main/java/.../sensors/HeartRateSource.kt, HealthConnectHrSource.kt
    app/src/main/java/.../routes/RouteTracker.kt, Segmenter.kt
    app/src/main/java/.../eta/PhysicsModel.kt, SegmentLearner.kt, EtaEngine.kt
    app/src/main/java/.../weather/OpenMeteoClient.kt
  analysis/eta_backtest.ipynb   # optional
```

---

## 8. Verification

- **Unit tests:**
  - `RollingWindow` (time-based, handles gaps)
  - route snapping and progress, including loops and GPS jitter
  - the physics solver against known values
  - the Bayesian update and Kalman filter converging on synthetic rides
  - ETA error on simulated rides with injected wind and lights
- **Replay mode:** a debug-only `SimulatedLocationSource` replays a recorded GPX at 1×–10× speed with synthetic HR, so the whole UI can be tested at a desk. Android Emulator GPX playback also works.
- **Health Connect:** use Health Connect Toolbox in the emulator to insert delayed HR records and check the freshness badge and backfill.
- **Screenshot tests** (Roborazzi) for the four screens in both portrait and landscape.
- **Field test:** ride home → work and back. The app logs every ETA prediction with a timestamp; afterwards, plot predicted vs actual on the Stats page (or in the notebook).

---

## 9. What was built (updated 5 Oct 2026)

It is still a native Kotlin and Compose app for one rider on one commute, and it does all four
things from the Context. How it does them drifted. The heart-rate side shrank once Phase 0 showed
live heart rate can't come from the Steel HR. The map was never built. The stack stayed much leaner
than section 2 planned. Meanwhile the commute itself grew features the plan never had: the app
works out which route you're on, finishes the ride when you arrive, and tells someone when you'll
be there.

### Stack

| Concern | Planned | Built |
|---|---|---|
| DI / navigation | Hilt, Navigation Compose | A hand-written `AppContainer` in `TegenwindApp.kt`; four bottom tabs, with detail screens opened from screen state |
| State | MVVM, a ViewModel with `StateFlow` per screen | `RideRecorder` exposes the ride as a `StateFlow`; the other screens read Room flows directly, without ViewModels |
| Persistence | Room plus DataStore | Room (database version 6, schemas exported to `app/schemas`, auto-migrations only) plus `SharedPreferences` for the last route and the "Share live" switch |
| Background work | WorkManager | Coroutines in the app's scope for map lookups and one-time backfills; a foreground service while riding |
| Charts / maps / geometry | Vico, MapLibre, spatial-k | Own Canvas charts, no map, own geometry in `routes/Geo.kt` (projection, Douglas-Peucker) |
| HTTP / JSON / math | Ktor, kotlinx.serialization, EJML | `HttpURLConnection`, `org.json`, plain Kotlin |
| Weather | Open-Meteo, KNMI model | Open-Meteo 15-minute data (hourly fallback) from its default model, with temperature, cloud cover and rain as well as wind |
| Testing / CI | JUnit 5, Turbine, Roborazzi, GitHub Actions | JUnit 4 unit tests only; no CI, installed over USB |

### Screens

The bottom bar is **Ride · Rides · Routes · Stats**. Rides replaced Map.

- **Ride, before setting off:** route picker (Auto-select, Free ride, or a saved route). A
  "Leave now, arrive at" card shows the arrival time ± band, the ride time, and the wind with what it
  costs. There's a "Share live" switch, Start ride, and a simulated ride to try it at a desk.
- **Ride, while riding:**
  - A status line and a clock.
  - A bar while auto-paused, and a bar on arrival with a countdown and "Keep riding".
  - The ETA card is the centre of the screen. It shows arrival ± band, a wind badge (head, tail
    or cross, with the speed at rider height and how sheltered you are), km to go, form and the wind's
    cost. Below that is a route bar coloured per segment by sky and rain on top and by what the wind
    does to you underneath, with a marker at your position. Tapping the card shares the ETA.
  - A speed chart headed by speed and distance: GPS dots and a 2-minute rolling **median**, over
    the last 2 minutes. Tapping it shows the whole ride on a reverse-log time axis. Under them, in
    amber, the speed the ETA expects: one line per segment, shaped within the segment by the
    route's learned speed profile (below). A small legend explains the lines.
  - A Stop button that needs two taps. The whole screen fits without scrolling.
  - Once the ETA is firm, a one-time "Share ETA via WhatsApp?" prompt.
  - The large speed number, 2-minute average and live heart rate from the plan are gone: the ETA
    is what you read on the handlebar.
- **Rides** (not in the plan): the list of finished rides. A ride's page shows distance, moving and
  total time, average speed, form and average heart rate. Under the total time it says where the
  ride ranks among the rides on its route: top or bottom x%, from five rides on. Then the speed
  chart and the heart-rate chart from Health Connect, and the "What this ride taught" card. It can
  also save the ride as a route, change which route it belongs to, give its route the line you
  actually rode, or delete it.
  A ride opens here by itself when it finishes.
- **Routes:** the list shows name, length and the state of the map lookup. Rename and GPX import
  are there. A route's page shows its traffic lights, km open to the wind and steepest slope, a
  wind-exposure strip, and a table per segment. The table includes what each segment learned: its
  time against your usual pace, from red (slower) through yellow to green (faster). It can add the
  reverse route, redo the lookup, or delete the route. Ride count, typical time and "Reset
  learning" were not built.
- **Stats:** as planned (route picker; fastest, slowest, average and median ride; trend;
  histogram; headwind vs duration; slowest segments), plus a fitness card that runs across routes.
  Its fitness and fatigue are in load/day: an hour of solid riding is about 100.
  Durations are start to finish, standing still included. "Slowest segments" compares against
  physics alone, not the learned expectation.

### Riding and routes

- **Recording** starts only when you set off (moving, or 25 m from where you tapped Start), so the
  time spent at the door isn't part of the ride. A ride that never sets off isn't kept.
- **Live pipeline:** as planned (Doppler speed, fixes worse than 20 m dropped, auto-pause below
  1.5 km/h after 5 s, screen kept on), except the rolling line is a median, which rides through GPS
  spikes, and there is no landscape layout.
- **Routes** come from a recorded ride (Douglas-Peucker at 5 m) or a GPX file. They can also be
  reversed, or given the line of a later ride when the road changed for good (this drops the
  segment times measured on the old line).
- **Segments** are equal stretches of about 250 m, without the planned extra cuts at slope changes
  or traffic lights.
- **The lookup** fetches elevation every 50 m (least-squares slope per segment), buildings within
  about 125 m of each segment's middle, and traffic lights within 25 m of the line (merged within
  40 m). Exposure is `0.15 + 0.85·e^(−buildings/25)`, so the range is 0.15–1.0 rather than 0.3–1.0.
  Crossings and road type are not looked up.
- **Tracking** takes hold within 40 m of the line and calls it off-route beyond 60 m. Progress only
  moves forward.
- **Auto-select** (`RouteMatcher`), instead of a guess at the start: every saved route is a
  candidate. Each is dropped after 300 m of riding that contradicts it, and can come back. Heading,
  and which end of a route you set off from, separate a route from its mirror. Past rides at this
  time of day break ties. Until one route is left, the likeliest carries the ETA, marked "still
  deciding", and no segment is timed.
- **Arrival:** within 30 m of the end, the ride finishes itself after 15 s unless you keep riding.
- **Speed profile** (28 Sep 2026): a bridge ramp or a sharp corner rides slower than the rest of its
  segment. Your rides showed these spots repeat reliably. Two halves of the rides agreed with a
  correlation of 0.93. Turns and altitude from the map explained only 9–16% of them, so each route
  learns its own profile: per 25 m, the moving speed relative to its segment's average, a fading
  average over about the last five rides. It was filled in from all stored rides at once. It only
  shapes the expected-speed line; each segment's time, and so the ETA, stays the segment model's.
- **Lines that follow your rides** (29 Sep 2026, not planned): a route's line is drawn once, from a
  GPX file or one ride, and then ridden a little differently.
  - Every real ride moves the line a quarter of the way toward where it went, where it went within
    40 m. That is a fading average over about the last seven rides, and it settles within a metre.
  - A stretch ridden on another street (more than 40 m away, leaving and rejoining the line) on two
    of the last three rides is rerouted: the line takes that street, and the ride's page says so.
  - What the route learned stays with the road it was learned on. Segments move with the line and
    keep their corrections, adjusted so their expected time doesn't change with their new length.
    The speed profile moves along too. Only the segments over a rerouted stretch start again, and
    the map lookup runs again for the new stretch.
  - The rides stored before this were folded in once at startup, as nudges only: replaying old
    rides could reroute a stretch back and forth. "Update route" still replaces the line at once.

### The ETA model

- **Layer 1** as planned. Rider parameters are constants (130 W, CdA 0.45, Crr 0.006, 90 kg); the
  planned learned `P_rider` isn't there, and the form factor does that job. Air density follows the
  temperature. Wind at rider height uses a log profile whose surface roughness follows the segment's
  exposure: about 65% of the 10 m wind in the open, about 30% between buildings.
- **Layer 2** is a single learned offset per segment (see Phase 6), learned from moving time with
  the day's form divided out. Traffic lights are not learned: every light costs a fixed 12 s on
  average.
  - Form and the offsets multiply, so a slowness all segments share could sit in either one.
    After every ride (and once at startup, 30 Sep 2026) that shared part moves into form. It is the
    average offset over all routes, weighted by length × passes. The offsets then average 0 over
    what you ride, and a new route starts at your real pace. Past rides' forms move by the same
    factor, and the starting form may range 0.5–1.6 (was 0.7–1.4), so the move is never cut short.
- **Layer 3:** the Kalman filter on today's form, as planned. The Banister model sets the starting
  form (±5% at most) instead of adjusting `P_rider`. There is no fatigue term within a ride and no
  cardiac drift.
- **Output:** as planned, the band being 1.28σ from form, segment, traffic-light and baseline
  uncertainty. There is no backtest and no log of predictions yet.

### Heart rate

Only after the ride. A ride's page reads its window from Health Connect each time it opens and
upgrades the ride's training load to TRIMP. No polling, freshness badge, `HeartRateSource` interface
or Bluetooth strap.

Since 28 Sep 2026 the readings of recent rides are also kept in the app, for **14 days** only. They
are read whenever the app comes to the front, and anything older is deleted at every start. They
are material for estimating heart rate during a ride:
`HR = a + b·effort + c·minutes`, with effort from the ETA's physics, smoothed with a 60–90 s delay.

A first test on 15 rides (leaving out one day at a time) found no useful model yet:
- the error was about 14 bpm, against 14.6 for always guessing the average
- your average heart rate differs by up to ±14 bpm from day to day, whatever the pace
- within a ride, effort explained only 8% of the heart rate's ups and downs

### Data

Room holds `rides`, `track_points`, `routes`, `route_points`, `route_segments` (with the learned
correction) and `segment_traversals`. A traversal stores the headwind and the physics prediction,
and since database version 6 also what the model expected before the ride and how far the pass
moved its segment. Standing time is `exit − enter − moving`; there's no average heart rate.

Database version 7 added `route_profile` (the speed profile, per 25 m) and `hr_samples` (the last 14
days of heart rate). Version 8 added `routes.lineFoldedThroughMs` (the last ride a route's line
learned from) and the stretch a ride rerouted (`rides.rerouteStartM`, `rerouteEndM`). The planned
`RiderState` and `ModelParams` tables don't exist: fitness is computed from ride loads when needed,
and rider parameters are constants.

### Beyond the plan

Everything above marked as not planned, plus:
- live sharing through meewind (section 7)
- the ETA shared as text with a live link or a map pin
- the simulator, which follows the route at the model's speed and stops at about half the traffic
  lights, instead of the planned GPX replay with synthetic heart rate

### Project layout

```
tegenwind/
  docs/PLAN.md, docs/prototype/index.html, docs/logo/
  app/schemas/                        # Room schema for every database version
  app/src/main/java/com/tegenwind/app/
    MainActivity.kt, TegenwindApp.kt (AppContainer), PermissionsRationaleActivity.kt
    ride/     RideService, RideRecorder, RideTracker, Rolling, AutoFinish, RideSimulator, LessonBackfill,
              ProfileLearning, LineLearning
    routes/   Geo, Gpx, RouteRepository, RouteEnricher, RouteTracker, RouteMatcher, RouteDrift
    eta/      Physics, EtaModel, SegmentLearner, LiveEta, Fitness
    weather/  WindForecast (Open-Meteo)
    live/     LiveShare, LiveJson (meewind)
    health/   HealthConnectHr
    data/     TegenwindDb (entities and DAOs)
    ui/       ride/, rides/, routes/, stats/, theme/, TimeSeriesChart
  app/src/test/java/com/tegenwind/app/  # JUnit 4 unit tests
```

### Verification

The JUnit 4 unit tests cover:
- the rolling median, auto-pause and GPS filtering
- route snapping and progress, auto-select, GPX, and turning a track into a route
- lookup retries, physics and the ETA, segment learning and the form filter
- the lessons backfill, arriving, and the meewind contract

Not built:
- Compose UI and screenshot tests
- tests for `RideRecorder`
- the Health Connect emulator checks
- the prediction log for field tests

Rides are tested with the simulator and on the phone.
