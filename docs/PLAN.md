# Bike Dashboard: plan

## Context

Your current tracking apps don't give you what you want on the handlebar. The goal is a personal Android app for commuting, shown on a phone mounted on the handlebar, with:

1. **Heart rate over time**: raw samples as a scatter plot plus a 2-minute rolling average line. The source is a Withings Steel HR.
2. **Speed over time**: from phone GPS, also scatter plus a 2-minute rolling average.
3. **Smart ETA** on saved routes ("home → work"). It accounts for position on the route, current pace, wind (open fields vs city), inclines and traffic, and it learns from every ride: which segments are slow or fast, fatigue during a ride, fitness over months.
4. **Route stats page**: fastest, slowest and average time per route, plus trends.

The project folder `bike-dashboard/` is empty, so this is a greenfield build.

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
| 2 | `HealthConnectHrSource`: permissions, including the required privacy-rationale activity; polling, freshness badge, post-ride backfill | ✅ done |
| 3 | Routes: record or import GPX, segmentation, elevation and OSM enrichment, map screen, route tracking | ✅ done (map screen is a lightweight Canvas schematic, not MapLibre) |
| 4 | ETA v1: physics plus Open-Meteo wind plus live pace, shown on the Ride screen | ✅ done |
| 5 | Stats dashboard | ✅ done |
| 6 | ETA v2: Bayesian segment learning, Kalman form, fatigue and fitness, uncertainty band, "what I learned" card | ◐ partial — the Kalman "today's form" filter and a simple ± uncertainty band already run on every ride; still missing: learned per-segment corrections (Layer 2) and long-term fitness/fatigue (Banister model) |
| 7 | Optional: BLE strap source, voice announcements, CSV export plus Jupyter backtest notebook | not started |

### Status (16 Sep 2026)

- **Phase 2 loose end closed:** the original permission-request UI lived in a one-off "HR test" debug tab (`hrprobe/`), built for the Phase 0 spike. That tab and its dead code were removed; requesting Health Connect access now happens inline on the ride detail screen (an "Allow access" button when heart rate can't be read yet), so there's no dependency on a debug-only screen any more.
- **Phase 5 built:** a Stats tab with a route picker, KPI tiles (fastest/slowest/average/median duration), a duration trend chart (fastest/slowest highlighted), a duration histogram, a headwind-vs-duration scatter, and a slowest-segments table (actual vs modeled time, from `SegmentTraversalEntity`).
- **Distribution today:** there's no CI/APK pipeline yet (no `.github/workflows`) despite it being in the tool choices above. The app reaches the phone by building locally and running `./gradlew installDebug` (or Android Studio's Run button) over a USB connection to the phone — this has been the deployment path since Phase 1, not something tied to a specific phase.

**Project layout**
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
