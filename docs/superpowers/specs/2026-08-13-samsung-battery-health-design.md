# Samsung Battery Health — Design

**Date:** 2026-08-13
**Status:** Approved
**Repository:** `mralaminahamed/samsung-battery-health` (private)
**Package:** `com.mralaminahamed.batteryhealth`

## Purpose

A native Android app that reports battery health and power behaviour on Samsung
phones, covering the ground Samsung's own battery settings covers, without
requiring root.

The organising constraint is honesty. Android exposes very little battery data to
unprivileged apps, and the numbers users most want — real state of health, cycle
count, per-app power — sit behind privileged APIs. Most apps in this category
paper over the gap with numbers derived from nothing. This app instead makes
availability an explicit, typed property of every metric, and shows the user
which tier each number came from.

## Success criteria

1. Installed with no permissions granted and no Shizuku present, the app shows
   live level, voltage, current, wattage, temperature, charge state and
   time-to-full, and records its own charge/discharge history.
2. After three qualifying charge sessions, it reports a measured full capacity
   and a health percentage, or explains precisely why it cannot.
3. With Shizuku bound, it additionally reports Samsung's ASOC and BSOH values,
   first-use date, Battery Protect state, and per-app power attribution.
4. No screen ever displays a numeral for a metric it does not actually have.
5. The `play` flavour builds and submits without `QUERY_ALL_PACKAGES`.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Data access | Unprivileged core, Shizuku as a bonus tier | Works for every user; richer data for those who opt in |
| v1 scope | Health, Live, History, Apps | Apps screen is Shizuku-gated |
| Visual direction | One UI native | Light canvas, white rounded cards, Samsung blue, bold collapsing title |
| Distribution | Play Store | Constrains permissions and the foreground service |
| Per-app usage | Two build flavours | `full` declares `QUERY_ALL_PACKAGES`; `play` does not |
| Sampling | Hybrid | 15-minute baseline worker plus an opt-in foreground recorder while charging |
| minSdk | 26 (raised from 24) | `java.time` without desugaring, notification channels, adaptive icons |
| Dynamic colour | Off | The fixed Samsung blue is the visual identity |

## Device baseline

Development and verification target: Galaxy A35 5G (`SM-A356E`), Android 16,
API 36, 5000 mAh design capacity.

Confirmed available to an unprivileged app on this device:

- Sticky `ACTION_BATTERY_CHANGED`: level, scale, status, health constant, plug
  type, voltage (3955 mV), temperature (37.1 °C), technology (Li-ion), present.
- `BatteryManager.getIntProperty`: `CHARGE_COUNTER` (2 095 000 µAh),
  `CURRENT_NOW`, `CURRENT_AVERAGE`, `ENERGY_COUNTER`.
- `BatteryManager.computeChargeTimeRemaining()`.
- API-gated properties attempted at runtime: `STATE_OF_HEALTH` (35+),
  `EXTRA_CYCLE_COUNT` (34+), `MANUFACTURING_DATE` and `FIRST_USAGE_DATE` (36+).
  The device's battery-changed broadcast reports `cycle_count: 0`, so cycle count
  is expected to be unavailable through the framework here.

Confirmed privileged-only, visible in `dumpsys battery`:

- `mSavedBatteryAsoc: 86` — Samsung's state-of-health percentage
- `mSavedBatteryBsoh: 95`
- `battery FirstUseDate: [20240630]`
- `mProtectBatteryMode: 1`, `mProtectionThreshold: 80`
- `Adaptive Fast Charging Settings: true`

Confirmed unavailable: `/sys/class/power_supply/battery/*` returns
`Permission denied` even to `adb shell`, so there is no sysfs route without root.
`DUMP` and `BATTERY_STATS` are `signature|privileged` and `pm grant` only handles
runtime permissions, so there is no adb-grant shortcut either. Shizuku is the
only unprivileged path to these values.

Regenerate the parser fixture with:

```bash
adb shell dumpsys battery > app/src/test/resources/dumpsys-battery-sm-a356e.txt
adb shell dumpsys batterystats --checkin > app/src/test/resources/batterystats-checkin-sm-a356e.csv
```

## Architecture

A single `:app` module with layered packages. Four screens do not justify a
multi-module build graph.

```
com.mralaminahamed.batteryhealth/
├── domain/          BatterySnapshot, HealthReport, ChargeSession, AppPower, Reading
├── data/
│   ├── framework/   BatteryBroadcastSource, BatteryManagerSource, CapabilityProbe
│   ├── privileged/  ShizukuGateway, DumpsysBatteryParser, BatteryStatsCheckinParser
│   ├── local/       Room database, entities, DAOs
│   └── repo/        BatteryRepository, HistoryRepository, HealthEstimator
├── sampling/        BaselineSampleWorker, ChargeRecorderService, PowerReceiver
└── ui/              theme/, components/, health/, live/, history/, apps/, nav/
```

### The availability model

`Reading<T>` is the spine of the application. No metric crosses into the UI as a
bare value.

```kotlin
sealed interface Reading<out T> {
    data class Available(val value: T, val source: Source) : Reading<T>
    data object Unsupported : Reading<Nothing>      // this device never provides it
    data object NeedsShizuku : Reading<Nothing>     // the privileged tier would
    data object NotYetMeasured : Reading<Nothing>   // needs more charge sessions
}

enum class Source { Framework, Measured, Privileged }
```

A metric that is absent renders as a dashed placeholder with a reason, never as a
zero and never as an invented estimate. `Source` is surfaced in the UI so that an
ASOC-derived 86% and a counter-measured 84% are visibly different kinds of claim.

Because availability is encoded in the type, the two data tiers do not fork the
UI. Screens render whatever each `Reading` reports, and the same composables
serve privileged and unprivileged users.

### Sources

- **`BatteryBroadcastSource`** — the sticky `ACTION_BATTERY_CHANGED` intent
  exposed as a `Flow`. Registered only while a screen is resumed; this broadcast
  cannot be declared in a manifest.
- **`BatteryManagerSource`** — `getIntProperty` reads plus the API-gated
  properties listed above. `CapabilityProbe` calls each once at startup and
  caches whether it returned a real value or a `-1` / `Integer.MIN_VALUE`
  sentinel, so the rest of the app never re-probes.
- **`ShizukuGateway`** — see below.

`BatteryRepository` merges all three into a single `BatterySnapshot` composed of
`Reading`s.

## Health measurement

Without Shizuku there is no ASOC to read, so health must be measured. While
charging, the recorder samples every five seconds. For a session running level
`L1 → L2` with charge counter `C1 → C2` in µAh:

```
fullCapacityUah = (C2 - C1) * 100 / (L2 - L1)
healthPct       = fullCapacityUah / (designCapacityMah * 1000) * 100
```

Charge counters are in µAh throughout; the design-capacity table is in mAh, so the
conversion is explicit at the one point where the two meet.

The guards matter more than the formula:

- **Reject sessions with `Δlevel < 20`.** Battery level is an integer
  percentage, so a five-point window carries roughly ±20% quantisation error.
- **Prefer wide sessions, then take a plain median.** From the qualifying set,
  if at least three sessions have `Δlevel ≥ 40`, use only those; otherwise use all
  sessions with `Δlevel ≥ 20`. Report the median of the chosen set — never the
  most recent session, since one charge near a thermal cap would otherwise swing
  the headline figure. Selection narrows the set; it does not weight it. There is
  no weighted average anywhere in this calculation.
- **Require at least three sessions.** Below that the reading stays
  `NotYetMeasured`, and the Health screen shows how many more are needed.
- **Detect a derived charge counter.** Some devices synthesise `CHARGE_COUNTER`
  from `level × designCapacity`, which makes the formula return the design
  capacity exactly and reports every battery as pristine. If three sessions with
  materially different `Δlevel` all land within 0.5% of design, treat the counter
  as derived and mark the reading `Unsupported`.
- **Coulomb-counting fallback.** When the counter is derived but `CURRENT_NOW` is
  real, integrate `Σ I·Δt` across the session instead. Estimates record
  `method = COULOMB` so the UI can distinguish them.
- **Design capacity is not readable from any API.** It ships as a `Build.MODEL`
  lookup table (`SM-A356*` → 5000 mAh) with a user override in settings. An
  unknown model with no override makes health `Unsupported`.

`HealthEstimator` is a pure function from `List<ChargeSession>` plus a design
capacity to `Reading<HealthReport>`. It contains no Android types, so every guard
above is unit-testable.

## Sampling engine

- **`BaselineSampleWorker`** — WorkManager periodic request, 15-minute interval
  (the platform floor), `KEEP` policy. Writes one sample row and runs retention
  pruning.
- **`ChargeRecorderService`** — a foreground service sampling every five seconds.
  `PowerReceiver` starts it on `ACTION_POWER_CONNECTED` when the user has opted
  in; it stops on `ACTION_POWER_DISCONNECTED`, or five minutes after status
  reaches `FULL`. On API 34+ it declares
  `foregroundServiceType="specialUse"` with a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` string describing charge measurement, which
  is also the basis of the Play declaration. Its notification shows live wattage
  and level so that it is useful to the user rather than pure compliance
  overhead.

The service is opt-in because a battery app that installs a permanent
notification and continuous wakeups undermines its own premise. Charging is the
one window where the cost is negligible and the data is irreplaceable.

## Persistence

Room, with exported schemas and real migrations. `fallbackToDestructiveMigration`
is prohibited: accumulated history is the only data in this app that cannot be
regenerated.

- **`samples`** — timestampMs, levelPct, chargeCounterUah, currentUa, voltageMv,
  tempDeciC, status, plugged, screenOn, nullable sessionId. Indexed on timestamp.
- **`sessions`** — type (`CHARGE` / `DISCHARGE`), startedAt, endedAt, startLevel,
  endLevel, startCounterUah, endCounterUah, peakTempDeciC, avgMilliwatts,
  screenOnMs.
- **`capacity_estimates`** — sessionId, measuredFullUah, deltaLevel, method
  (`COUNTER` / `COULOMB`), trustworthy.

Retention: samples for 45 days, sessions for one year, pruned by the baseline
worker. Sample retention deliberately exceeds the History screen's longest range
(30 days) so that the oldest visible point is never a partially pruned window.

## Privileged tier

`dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider`, permission
`moe.shizuku.manager.permission.API_V23`.

Rather than spawning a process per query, the app binds a **UserService** once
via `Shizuku.bindUserService`. It runs in the shell UID and exposes a small AIDL
surface: `dumpBattery(): String` and `dumpBatteryStatsCheckin(): String`.
Binder-received and binder-dead listeners feed a `Flow<PrivilegeState>`, so
Shizuku disappearing mid-session flips every privileged `Reading` to
`NeedsShizuku` live, without a crash.

Both parsers are pure functions over strings:

- **`DumpsysBatteryParser`** extracts `mSavedBatteryAsoc`, `mSavedBatteryBsoh`,
  `battery FirstUseDate`, `mProtectBatteryMode`, `mProtectionThreshold` and the
  charging-mode flags. Every field is independently optional, so a One UI update
  that renames one line degrades a single metric rather than the screen.
- **`BatteryStatsCheckinParser`** consumes `dumpsys batterystats --checkin`,
  which is stable CSV, rather than the human-readable dump's prose.

The app reads privileged state but never writes it. Battery Protect, charging
modes and power-saving are Samsung-private settings; a third-party write is
unreliable and can silently no-op, so those rows display current state and
deep-link into the corresponding Settings screen.

## UI system

**Typeface: the system default, deliberately.** On a Samsung device
`FontFamily.Default` resolves to SamsungOne / SamsungSans, so the app inherits
the exact face One UI uses at no cost and reads as native rather than imitative.
Numerals set `FontFeatureSetting("tnum")` so digits do not jitter as values
update.

Tokens:

| Token | Light | Dark |
|---|---|---|
| canvas | `#F3F4F6` | `#000000` |
| card | `#FFFFFF` | `#1B1D1F` |
| accent | `#0F62FE` | `#5A9BFF` |
| textPrimary | `#1B1D20` | `#F5F6F7` |
| textSecondary | `#6A7078` | `#9AA0A8` |
| divider | `#EEF0F3` | `#2A2D31` |
| good | `#0F9D58` | `#3DD68C` |
| fair | `#F5A623` | `#FFB84D` |
| poor | `#E5484D` | `#FF6369` |

Components form a thin layer over Material 3, so ripple, touch targets, gesture
handling and accessibility semantics come from the platform:

- `CollapsingTitleScaffold` — M3 `LargeTopAppBar` with `exitUntilCollapsed`,
  restyled to One UI's bold 27sp title over a transparent canvas
- `OneUiCard` — 24dp radius, 16dp padding, hairline shadow
- `SectionHeader` — small uppercase accent label
- `KeyValueRow` — secondary label left, bold tabular value right, divider
- `BigMetric` — oversized numeral with baseline-aligned unit
- `ProgressTrack` — 9dp rounded bar
- `ReadingSlot` — renders all four `Reading` states uniformly. Every metric on
  every screen passes through it, so "unavailable" cannot accidentally be styled
  as data.
- `UnlockCard` — Shizuku explainer, shown only where a `NeedsShizuku` reading
  exists
- Charts: hand-rolled Compose `Canvas` composables — `LevelHistoryChart` (area
  line) and `SessionBarChart`. History needs exactly two chart forms, both
  simple, and drawing them directly removes a dependency while giving the precise
  control the One UI styling wants.

Libraries: Compose BOM 2026.02.01, Material 3, navigation-compose, Room with KSP,
WorkManager, Hilt, DataStore Preferences, Shizuku API.

## Screens

Bottom navigation with four destinations.

**Health.** Hero card: health percentage, a `Source` chip distinguishing ASOC
from measured, measured capacity against design capacity, progress track. A
*Battery information* card: cycle count, first-use date, age, BSOH, technology,
manufacturing date. A *Condition* card: temperature, voltage, Battery Protect
mode and threshold. Unprivileged users see `NotYetMeasured` with a count of
remaining charge sessions until the estimator has enough data.

**Live.** Wattage as the hero figure, computed from volts × amps, then current,
voltage, temperature, charge state and time-to-full. A rolling 60-second current
sparkline. Charging class (Adaptive Fast, Super Fast) when privileged.

**History.** Level-over-time chart across 24-hour, 7-day and 30-day ranges, with
a session list below. Charge rows show start and end level, duration, mAh added,
average wattage and peak temperature. Discharge rows show drain per hour split
between screen-on and screen-off. Tapping a row opens its detail.

**Apps.** Per-UID power attribution from `batterystats`, sorted, with each app's
share of total. Labels and icons resolve fully in the `full` flavour; the `play`
flavour resolves what is visible without `QUERY_ALL_PACKAGES` and falls back to
package names. The whole screen reports `NeedsShizuku` until the gateway binds.

## Build flavours

Shared code lives in `src/main`. Two flavours differ only in package visibility:

- **`full`** — `src/full/AndroidManifest.xml` declares `QUERY_ALL_PACKAGES`;
  `AppLabelResolver` queries `PackageManager` freely. Distributed outside Play.
- **`play`** — no such permission; `AppLabelResolver` resolves what is visible
  and falls back to the raw package name with a generic icon.

Both share one `applicationId`. `AppLabelResolver` is the only type that differs,
so nothing else in the codebase knows which flavour it is running in.

This split exists because Play approves `QUERY_ALL_PACKAGES` only for a short
list of app categories that does not clearly include battery tools. Keeping the
permission out of the Play flavour removes submission risk without giving up the
richer build.

## Failure handling

- Sources never throw across their boundary. Failure is expressed as a `Reading`
  value, so the UI has no error path to forget.
- A parser that matches nothing logs and yields `Unsupported` for that field
  alone.
- Shizuku binder death flips privileged readings to `NeedsShizuku` while screens
  are live.
- `ForegroundServiceStartNotAllowedException` under API 34+ background-start
  rules is caught; the recorder degrades to baseline sampling and the Health
  screen explains why a session was missed.
- Room migrations are written and tested, never destructive.

## Testing

Unit tests:

- `DumpsysBatteryParser` against the captured `SM-A356E` fixture, asserting
  ASOC 86, BSOH 95 and first-use date 2024-06-30 from genuine device output.
- `BatteryStatsCheckinParser` against a captured checkin fixture.
- `HealthEstimator`: the `Δlevel < 20` rejection, median-of-three selection,
  derived-counter detection, coulomb fallback, and `Unsupported` for an unknown
  model with no override.
- `CapabilityProbe` sentinel handling.
- Session segmentation from a synthetic sample stream.

Instrumented tests: DAO round-trips and Room migration tests.

Compose tests: `ReadingSlot` renders each of the four states, and a screen-level
assertion that no numeral is displayed while its `Reading` is not `Available`.
That assertion is the regression guard on the app's central premise.

Manual verification: `SM-A356E` on API 36, with and without Shizuku bound.

## Out of scope for v1

- Writing Samsung-private settings (Battery Protect, charging modes, power
  saving). Read and deep-link only.
- Widgets, tiles, and Wear support.
- Root-based sysfs reading.
- Battery-usage alerts and notifications beyond the recorder's own.
- Localisation beyond English.
