# Samsung Battery Health

An Android app for inspecting battery health on Samsung devices.

> **Status: in progress.** The data layer is built and tested — battery sources, Room
> storage, settings, and the capacity estimator. The Health, Live and History screens are
> not written yet, so `MainActivity` still renders a placeholder.

## What it can and cannot know

Android exposes very little battery data to an unprivileged app, and the numbers users most
want are the ones it withholds. This matters enough to state up front, because it shapes the
whole design.

Readable by any app: charge level, voltage, current, temperature, technology, charge counter,
and charge-time-remaining.

**Not readable by any unprivileged app, on any device:** state of health, first-use date, and
manufacturing date. All three sit behind `BATTERY_STATS`, a signature-level permission, and
two of them are `@SystemApi @hide` so their constants appear in no public SDK. Verified
against real hardware, not assumed from API levels.

Consequently the app **measures** health rather than reading it: it samples the charge
counter across charge sessions and derives full capacity, comparing against a design capacity
looked up from `Build.MODEL`. Every guard in that calculation exists to stop a specific way it
could produce a confident wrong answer — narrow charge windows are rejected, the median of
several sessions is used rather than the latest, a charge counter synthesised from the level
is detected and refused, and an implausible result is reported as unavailable rather than
clamped into a believable-looking number.

Metrics that need the privileged tier report "needs privileged access" rather than "unavailable on this
device", because a permission denial is not a hardware limitation.

## Requirements

| Tool                   | Version                                 |
|------------------------|-----------------------------------------|
| JDK                    | 25 (Android Studio's bundled JBR works) |
| Android Gradle Plugin  | 9.3.1                                   |
| Gradle                 | 9.7.0 (via wrapper)                     |
| Kotlin                 | 2.4.10                                  |
| compileSdk / targetSdk | 37                                      |
| minSdk                 | 26 (Android 8.0)                        |

Dependencies track their latest stable versions; see `gradle/libs.versions.toml`.

## Getting started

Clone the repo and create `local.properties` pointing at your Android SDK — the file is
intentionally untracked:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Building from Android Studio

Open the project directory and let Gradle sync. Everything else is configured by the version
catalog in `gradle/libs.versions.toml`.

### Building from the command line

The Gradle wrapper needs a JDK on `PATH`. If you have no system-wide JDK installed, point
`JAVA_HOME` at the runtime bundled with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

### Build flavours

There are two, so **every Gradle task name is flavour-qualified**:

- **`play`** (default) — omits `QUERY_ALL_PACKAGES`, keeping it out of a Play submission.
- **`full`** — declares it, so per-app battery attribution can resolve app names and icons.

Build and install a debug APK on a connected device:

```bash
./gradlew installFullDebug   # or installPlayDebug for the Play-safe flavour
```

Other useful tasks:

```bash
./gradlew assemblePlayDebug            # build without installing
./gradlew testPlayDebugUnitTest        # host-side unit tests
./gradlew connectedPlayDebugAndroidTest # instrumented tests on a connected device
```

Two things that will otherwise cost you time:

- `connectedPlayDebugAndroidTest` does **not** accept `--tests`. Filter with
  `-Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.ClassName>`.
- The device must be awake and unlocked, or Compose tests fail with a misleading
  "No compose hierarchies found". Run
  `adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard` first.
- The connected-test task **uninstalls the app** when it finishes. Reinstall before launching
  it by hand.

Launch the installed app from a shell:

```bash
adb shell am start -n com.mralaminahamed.batteryhealth/.MainActivity
```

## Project layout

```
app/src/main/java/com/mralaminahamed/batteryhealth/
├── MainActivity.kt          Entry point; installs the splash screen
├── BatteryHealthApplication.kt
├── domain/                  Reading<T>, BatterySnapshot, HealthReport, ChargeSession
├── data/
│   ├── framework/           Battery broadcast + BatteryManager sources, capability probe
│   ├── local/               Room entities, DAOs, database
│   ├── settings/            DataStore preferences, design-capacity table
│   └── repo/                HealthEstimator
├── di/                      Hilt modules
└── ui/
    ├── theme/               Tokens, typography, BatteryHealthTheme
    └── components/          One UI card/row/metric primitives, ReadingSlot
```

- `applicationId` / `namespace`: `com.mralaminahamed.batteryhealth`
- UI is entirely Jetpack Compose with Material 3 (Compose BOM 2026.08.00); there are no XML
  layouts beyond the splash theme.
- Dynamic colour is deliberately off — the fixed Samsung blue is the product identity.
- Every metric reaches the UI wrapped in `Reading<T>`, which carries either a value plus its
  provenance or a specific reason for absence. `ReadingSlot` is the only sanctioned way to
  render one, and it invokes its content lambda solely for available readings, so a missing
  metric cannot be styled as data by accident.
- Room uses exported schemas and real migrations; destructive migration is prohibited,
  because recorded history is the only data here that cannot be recomputed.
- R8 optimization is currently disabled for release builds in `app/build.gradle.kts`. Enable
  it before shipping.

## Verified on

- Samsung Galaxy A35 5G (SM-A356E), Android 16 (API 36)
