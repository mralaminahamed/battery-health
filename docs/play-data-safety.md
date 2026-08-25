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

### `INTERNET`

Required to open a socket to the on-device Android Debug Bridge daemon at `127.0.0.1`. Android
enforces `INTERNET` at socket creation regardless of destination and does not exempt loopback,
so an app cannot connect to its own device without it.

No outbound network requests are made. This is enforced by an automated test, not merely
asserted.

### `FOREGROUND_SERVICE_SPECIAL_USE`

Declared with a matching `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`
in the manifest. Justification, which should be pasted into the Play Console declaration
verbatim:

> Samples battery charge counters every five seconds while charging to measure full capacity,
> which cannot be derived from 15-minute periodic work.

The measurement this app exists to perform requires charge-counter deltas sampled densely
across a charge session. `WorkManager`'s minimum periodic interval is 15 minutes, which is far
too coarse: at that cadence the counter deltas are dominated by noise and the derived capacity
is not trustworthy. No other foreground service type describes this use.

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

**"Why does a battery app need INTERNET?"** See above — loopback only, enforced by test.
