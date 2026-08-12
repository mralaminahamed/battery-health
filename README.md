# Samsung Battery Health

An Android app for inspecting battery health on Samsung devices.

> **Status: early scaffold.** The project currently contains the Jetpack Compose
> application shell only — no battery-reading logic has been written yet.
> `MainActivity` renders a placeholder `Greeting` composable.

## Requirements

| Tool                   | Version                                 |
|------------------------|-----------------------------------------|
| JDK                    | 25 (Android Studio's bundled JBR works) |
| Android Gradle Plugin  | 9.3.1                                   |
| Gradle                 | 9.5.0 (via wrapper)                     |
| Kotlin                 | 2.2.10                                  |
| compileSdk / targetSdk | 37                                      |
| minSdk                 | 24 (Android 7.0)                        |

## Getting started

Clone the repo and create `local.properties` pointing at your Android SDK — the
file is intentionally untracked:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

### Building from Android Studio

Open the project directory and let Gradle sync. Everything else is configured by
the version catalog in `gradle/libs.versions.toml`.

### Building from the command line

The Gradle wrapper needs a JDK on `PATH`. If you have no system-wide JDK
installed, point `JAVA_HOME` at the runtime bundled with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Then build and install a debug APK on a connected device:

```bash
./gradlew installDebug
```

Other useful tasks:

```bash
./gradlew assembleDebug   # build the APK without installing
./gradlew test            # host-side unit tests
./gradlew connectedCheck  # instrumented tests on a connected device
```

Launch the installed app from a shell:

```bash
adb shell am start -n com.mralaminahamed.batteryhealth/.MainActivity
```

## Project layout

```
app/src/main/java/com/mralaminahamed/batteryhealth/
├── MainActivity.kt          Entry point; edge-to-edge Scaffold
└── ui/theme/
    ├── Color.kt             Static light/dark palette
    ├── Theme.kt             BatteryHealthTheme, dynamic color on Android 12+
    └── Type.kt              Material 3 typography
```

- `applicationId` / `namespace`: `com.mralaminahamed.batteryhealth`
- UI is entirely Jetpack Compose with Material 3 (Compose BOM 2026.02.01); there
  are no XML layouts.
- R8 optimization is currently disabled for release builds in
  `app/build.gradle.kts`. Enable it before shipping.

## Verified on

- Samsung Galaxy A35 5G (SM-A356E), Android 16
