# Play Console — Data safety answers

Draft answers for the Data safety form. Every claim here is checkable against the source; the
notes say where.

## Data collection and sharing

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A — no data is transmitted |
| Do you provide a way for users to request that their data is deleted? | N/A — nothing is collected; uninstalling or Clear data removes everything |

"Collect" in Play's definition means transmitting data off the device. This app transmits
nothing: it makes no outbound network requests, and the only socket it ever opens goes to
`127.0.0.1`.

Battery samples and charge sessions are written to the app's private on-device storage
(Room + DataStore). Under Play's definitions that is not collection, because it never leaves
the device.

**Evidence:** `PrivilegedShellLoopbackTest` fails the build if any production source
constructs a socket outside `AdbConnection`, or constructs one with a named host rather than
`InetAddress.getLoopbackAddress()`. There are no analytics, crash-reporting, advertising, or
other third-party SDKs — `gradle/libs.versions.toml` contains only AndroidX, Google and Kotlin
artifacts.

## Permission declarations

### `INTERNET` — **not declared in the Play build**

The Play flavour compiles in no network code at all and ships without this permission. There
is nothing to justify at review: the shipped package cannot open a socket to any address.

The `full` flavour, distributed outside Google Play, declares it for one purpose — a socket to
`127.0.0.1` so the optional privileged tier can reach the device's own `adbd`. Android
enforces `INTERNET` at socket creation regardless of destination and does not exempt loopback.

`PrivilegedShellLoopbackTest` fails the build if any production source constructs a socket
outside `AdbConnection`, or constructs one with a named host rather than
`InetAddress.getLoopbackAddress()`.

### `BATTERY_STATS`

Declared, and **not held by default**. Its protection level is
`signature|privileged|development`, so it can never be granted by a user tapping
something and is never granted on a normal install. The `development` flag means it can be
granted over adb:

```
adb shell pm grant com.alaminahamed.batteryhealth android.permission.BATTERY_STATS
```

That is an explicit, deliberate action taken by the device's owner from their own computer.
Ungranted, the declaration is inert: the app behaves exactly as it does without it, and the
three affected readings (state of health, first-use date, manufacturing date) report as
unavailable.

Justification if a reviewer asks: the app's stated purpose is reporting battery health
honestly, and these are the values that make that possible. It does not fail, degrade, or
nag when the permission is absent — it says the readings are unavailable, which is the same
thing it does for every other value it cannot obtain.

Nothing read under this permission leaves the device. The battery serial number is
deliberately **not** recorded even into the app's own diagnostic report — it is a
per-device identifier, and the report is meant to be shareable.

### `RECEIVE_BOOT_COMPLETED`

Re-registers the sampling schedule after a restart, so recorded history is not silently
interrupted by a reboot.

### `QUERY_ALL_PACKAGES` — **Play build: not declared**

The `play` product flavour deliberately omits this. It is declared only in the `full` flavour,
which is distributed outside Google Play, where it resolves package names to app labels and
icons for per-app battery attribution.

If a Play submission ever needs it, be aware that Play does not accept battery attribution as
a permitted use of `QUERY_ALL_PACKAGES`. Keep it out of the `play` flavour.

## Sensitive-permission summary

The app requests no runtime permissions in the dangerous group — no location, contacts,
camera, microphone, storage, or phone. `POST_NOTIFICATIONS` is requested to show the sampling
foreground-service notification.

## Points a reviewer may raise

**"The app instructs users to enable ADB debugging."** The privileged tier is optional. The
app is fully functional without it and reports the affected metrics as unavailable rather than
estimating them. When enabled, it acts as a client to the user's own device's `adbd` over
loopback, authenticating with an RSA key held non-exportably in the Android Keystore.

The privileged surface is exactly two commands, `dumpsys battery` and
`dumpsys batterystats --checkin`, compiled in as constants. No method in the codebase accepts
a command string, so no caller-supplied or data-derived text can reach a shell. This is a
structural property, not a filter that could be bypassed.

**"Why does a battery app need INTERNET?"** The Play build does not have it. See above.
