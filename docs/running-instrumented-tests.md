# Running the instrumented tests

The `androidTest` suite needs a real device. It was written long before one was available
and first executed on an SM-S948B (Android 16), which surfaced several failures that were
device conditions rather than product defects. Set the device up first, or you will spend
the time re-diagnosing them.

## Device preconditions

**The screen must be awake and unlocked for the whole run.** Compose tests cannot find any
composition while the display is dozing, and they fail with:

```
IllegalStateException: No compose hierarchies found in the app.
Possible reasons include: (1) the Activity that calls setContent did not launch; ...
```

That message names the Activity and never mentions the screen. A default 30-second
timeout turned a fully green suite into 47 failures spread across unrelated classes, in a
different combination on each run — indistinguishable, at a glance, from flaky product
code.

```sh
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
```

**Battery saver must be off.** It blocks the foreground service `ChargeRecorderService`
starts, so `ChargeRecorderServiceTest` times out waiting for a service that the system
refused to start.

```sh
adb shell settings put global low_power 0
```

This one is worth remembering beyond the tests: the sampling this app's capacity
measurement depends on is a foreground service, and a user in battery saver does not get
it either.

## Running

```sh
./gradlew connectedDebugAndroidTest
```

One class only:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.alaminahamed.batteryhealth.ui.health.DesignCapacityDialogTest
```

## The discovery sweep

`BatteryDiscoveryOnDeviceTest` is the one test whose value is its output rather than its
assertions. It runs the full sweep and prints everything the attached device offered:

```sh
adb logcat -c
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.alaminahamed.batteryhealth.data.vendor.discovery.BatteryDiscoveryOnDeviceTest
adb logcat -d -s BatteryDiscovery
```

Its assertions stay device-independent on purpose. Asserting that a particular property is
readable would encode one phone's answer as every phone's expected behaviour, which is the
assumption the whole feature exists to stop making.

## Verify twice

Several of the failures found here appeared in a different combination on each run. A
single green run is not evidence that a flaky suite is fixed; run it at least twice.
