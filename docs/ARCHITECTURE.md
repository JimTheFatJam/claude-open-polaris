# Architecture

## Module layout

```
Polaris
├── app/                 Android application (Jetpack Compose UI, ViewModels, Android services)
├── core/                Pure Kotlin/JVM: domain models, astronomy, scoring, weather client & cache
└── astronomy-engine/    Vendored Astronomy Engine (Kotlin/JVM, MIT), unmodified
```

```
          ┌──────────────────────── app ────────────────────────────┐
  UI      │ ui/tonight  ui/sky  ui/explore  ui/telescope  ui/settings│  Compose screens
          │        │  ViewModels (StateFlow)  │                      │
  Data    │ ObservingRepository  SettingsRepository (DataStore)     │  single source of truth
          │ DeviceLocationProvider (LocationManager)                 │
          │ OrientationSensor (SensorManager → Flow)                 │
          └───────────────┬─────────────────────────────────────────┘
                          │ depends on
          ┌───────────── core (no Android) ─────────────────────────┐
          │ model/      GeoLocation, WeatherSample, DataQuality, …   │
          │ astro/      SkyCalculator, Catalog, VisibilityModel      │
          │ scoring/    ScoringEngine, NightForecaster               │
          │ weather/    OpenMeteoClient, WeatherRepository, cache    │
          │ telescope/  PointingMath, AlignmentOffset, smoothing     │
          └───────────────┬─────────────────────────────────────────┘
                          │
                 astronomy-engine (ephemerides, rise/set, twilight)
```

### Why a pure-JVM `core`
Everything that can be wrong in a way that matters (astronomy, scoring, parsing, caching and
pointing math) lives in a module with **no Android dependency**. It runs as plain JUnit tests in
seconds, without an emulator or Robolectric. The Android layer is kept thin: it adapts platform
services to flows and renders state.

### Why vendor Astronomy Engine
Astronomy Engine is accurate (validated against JPL Horizons/NOVAS to arc-second level), has no
dependencies, and is MIT licensed. Its Kotlin artifact is published on JitPack rather than Maven
Central. Vendoring the single source file (unmodified, with its licence) gives reproducible
builds without an extra repository, at the cost of updating it by hand.

## Data flow

1. `SettingsRepository` (Preferences DataStore) emits location mode, manual location, Bortle
   class, units, auto-refresh and phone mounting axis.
2. `ObservingRepository` (app-scoped) resolves a location: the manual setting, or
   `DeviceLocationProvider` when permission is granted. The last device fix is persisted, so
   results appear immediately on the next launch.
3. `WeatherRepository` returns a forecast using a cache-first policy (below).
4. `NightForecaster` combines the forecast with `SkyCalculator` geometry into a
   `ConditionsReport`: current assessments, hourly assessments until sunrise, twilight times and
   best windows. Every catalog object is also assessed for the Sky/Explore screens.
5. ViewModels expose `StateFlow`s that Compose collects with `collectAsStateWithLifecycle`.

Astronomy is computed **locally**, so Sky, Explore and the twilight/Moon data work fully offline.
Only the weather-dependent scores need the network.

## Weather integration

- **Provider abstraction**: `WeatherProvider` (`fetchRaw` + `parse`) and `PlaceSearch`.
  `OpenMeteoClient` implements both. Swapping in or adding a provider (e.g. MET Norway) needs no UI
  change.
- **Request**: hourly + current variables, `timezone=auto`, `timeformat=unixtime`, 3 forecast days.
  No API key is needed; nothing secret is embedded.
- **Typed errors**: `WeatherException.Network / RateLimited(retryAfter) / Http(code) / Parse`.
  The UI explains each one.
- **Caching** (`WeatherRepository` + `FileWeatherCache`):
  - The raw JSON is cached per ~1 km grid cell, written atomically.
  - Data younger than 30 min is served without a network call.
  - Forced refreshes (pull-to-refresh) are throttled to one per 60 s.
  - HTTP 429 starts a back-off (Retry-After, else 15 min) during which the cache is served.
  - On failure, cached data up to 36 h old is shown with an "offline / cached" banner and its age.
- Units are fixed internally (°C, km/h, mm, m). `Formatter` converts them only for display.

## Location & time zone

- Only `ACCESS_COARSE_LOCATION` is requested (~3 km is ample). The platform `LocationManager` is
  used (fused provider on Android 12+, network/GPS fallback, last-known fallback) to avoid a
  Google Play Services dependency.
- Permission denied → the manual picker (Open-Meteo geocoding, or typed coordinates offline).
- Time zone: the place's IANA zone if known (geocoding), otherwise the zone Open-Meteo reports for
  the coordinates, otherwise the device zone. All times are shown in the **observing site's**
  zone.

## Telescope assistant (optional, isolated)

- `OrientationSensor` prefers `TYPE_ROTATION_VECTOR` (gyro/accel/magnetometer fusion) and falls
  back to accelerometer + magnetometer. Missing hardware gives `OrientationSource.NONE` and an
  explanatory screen; no crash.
- `PointingMath` maps the device→ENU rotation matrix and the chosen phone axis (top edge or rear
  camera) to azimuth/altitude. It corrects magnetic declination with Android's
  `GeomagneticField` (World Magnetic Model) and smooths azimuth circularly.
- **Two navigation modes** (`PhoneGuidance`). *Camera*: the rear-camera axis R·(0,0,−1) as az/alt,
  with a push-to guide. *Phone plane*: the target t expressed in phone coordinates (Rᵀt); its
  out-of-plane angle asin(t_z) is the single quantity to zero, which is invariant to spinning the
  phone within its plane. For pinpoint accuracy the target must also lie on the plane's most
  vertical line v = u − (u·n)n, which then acts as the pointing direction (az/alt guide: turn about
  the vertical, steeper/flatter). The edge to tilt follows the in-plane direction of t, and
  "raise/lower" wording comes from the screen normal's vertical component (gravity). See [TELESCOPE.md](TELESCOPE.md).
- Heading source: `TYPE_ROTATION_VECTOR` (compass, declination-corrected) or
  `TYPE_GAME_ROTATION_VECTOR` (gyro only). A one-star camera-style sync measures a session heading
  correction used by both modes. Syncs average 1.5 s of readings and are rejected if the phone
  moved more than 0.6°. The rotation matrix is low-pass filtered and re-orthonormalised.
- **Telescope sync** (`TelescopeSync`): syncs on centred objects are stored as `StoredSighting`
  (rotation matrix + target, JSON in DataStore; session-only in gyro mode). They are solved with
  `MountingCalibration.solve` into a phone-frame telescope axis plus a heading correction. Plane mode
  then guides with R·d. A new sync that disagrees by more than 5° replaces the old ones (the phone moved).
- **Live tracking**: the Telescope screen recomputes object positions every second.
  `SkyCalculator.motion` gives azimuth/altitude rates. Sync captures use the target position at the
  middle of the capture window.
- Interference detection: field magnitude outside 20–70 µT, and sensor accuracy status.
- Nothing in the stargazing features depends on this module.

## UI

- Jetpack Compose with Material 3 and a custom dark palette (contrast-checked text colours).
  Navigation uses a bottom bar with five destinations (`navigation-compose`).
- A decorative starfield background (seeded, slow twinkle on a few stars) is clearly ornamental.
  The sky map is the only depiction of the real sky.
- States: loading, permission rationale, location unavailable, offline/cached, rate-limited,
  data unavailable ("—" with "No data"), and pull-to-refresh.
- No sample data ships in production code. The fixture forecast exists only in tests.

## Dependency injection

`AppContainer` is a small manual container created in `PolarisApp`. It accepts an optional
`WeatherProvider`, `PlaceSearch` and `Clock`, which is how the Robolectric render test injects a
deterministic forecast and a fixed time. Hilt could replace it if the graph grows.

## Testing

| Suite | Location | What |
|---|---|---|
| Astronomy | `core/src/test/.../astro` | Sun altitude, sunrise/sunset vs USNO (±3 min), twilight ordering, polar day/night, no-astronomical-darkness, Moon phases vs published dates, fixed-object rise/set consistency, circumpolar cases, catalog sanity |
| Scoring | `core/src/test/.../scoring` | category thresholds, gates, monotonicity in cloud, bounds with extreme inputs, missing-data handling, observation-type differences, best windows |
| Weather | `core/src/test/.../weather` | JSON parsing incl. nulls, MockWebServer 429/400/network errors, cache freshness, throttling, back-off, stale fallback, file cache |
| Telescope | `core/src/test/.../telescope` | rotation-matrix → az/alt, declination, wrap-around, alignment, smoothing; camera pointing and sync; plane offset, in-plane-spin invariance, 'following the instruction aligns', back-side targets, vertical-screen wording; mounting-calibration library tests |
| UI smoke | `app/src/test/.../ScreenRenderTest.kt` | Robolectric renders all five screens with a fixture forecast; also produces screenshots |

## Versions

AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1 · compileSdk/targetSdk 35 · minSdk 26 ·
Compose BOM 2024.12.01 · OkHttp 4.12 · kotlinx.serialization 1.7.3 · DataStore 1.1.1 ·
Navigation 2.8.5 · Robolectric 4.14.1. These are well-tested, stable releases chosen for
compatibility rather than recency.

## Extending

- New weather provider → implement `WeatherProvider`.
- New observation type → add to `ObservationType`, a weights row, and gates if needed.
- Bigger catalog → `Catalog` (or load a bundled CSV/JSON through the same `CelestialObject` model).
- Persistent session logs/favourites → add Room alongside DataStore.
