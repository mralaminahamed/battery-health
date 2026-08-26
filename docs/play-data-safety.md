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
nothing: it makes no outbound network requests and opens no sockets at all.

Battery samples and charge sessions are written to the app's private on-device storage
(Room + DataStore). Under Play's definitions that is not collection, because it never leaves
the device.

**Evidence:** there are no analytics, crash-reporting, advertising, or other third-party SDKs —
`gradle/libs.versions.toml` contains only AndroidX, Google and Kotlin artifacts. Neither build
flavour declares `INTERNET`.

## Permission declarations

### `INTERNET` — **not declared in either flavour**

An earlier version of this app carried an on-device adb client that opened a loopback socket
to reach permission-gated `dumpsys` output, and the `full` flavour declared `INTERNET` for
that one purpose. That client, and every permission-gated shell read it existed to reach, has
been removed: this app now asks for nothing beyond what a normal Android permission flow or a
public API can supply. Neither flavour opens a socket, and neither declares `INTERNET`.

### `PACKAGE_USAGE_STATS`

Appop-gated, not a runtime-dialog permission. Declaring it grants nothing by itself; the user
must flip it on from the system's own Settings → Apps → Special access → Usage access screen,
which this app deep-links to (`Settings.ACTION_USAGE_ACCESS_SETTINGS`). Held or not, the app
behaves the same way it does for any other permission it lacks: the affected section reports
its state honestly rather than nagging.

### `POST_NOTIFICATIONS`

A real runtime dialog (API 33+), requested only when the user opts in to the charge-session
recorder. The notification it shows is the honest signal that recording is running in the
background.

### `RECEIVE_BOOT_COMPLETED`

Re-registers the sampling schedule after a restart, so recorded history is not silently
interrupted by a reboot.

### `QUERY_ALL_PACKAGES` — **Play build: not declared**

The `play` product flavour deliberately omits this. It is declared only in the `full` flavour,
which is distributed outside Google Play, where it resolves package names to app labels and
icons for the Apps screen's per-uid CPU-time list.

If a Play submission ever needs it, be aware that Play does not accept this as a permitted use
of `QUERY_ALL_PACKAGES` for a battery tool's declared category. Keep it out of the `play`
flavour.

## What this app no longer declares, and why

Two permissions were removed entirely. Both were `signature|privileged|development` protection
level, meaning the only way any app can ever hold either is `adb shell pm grant` run from a
computer — never a tap by the user, on any device, at any time.

- **`BATTERY_STATS`** used to unlock state of health, first-use date and manufacturing date,
  read directly from `BatteryManager` once granted. Declaring a permission that can never be
  held by a real installed copy of this app was declaring it for nothing, so it is gone. Those
  three readings now report as unavailable unconditionally (state of health has a narrow,
  genuinely unprivileged exception — see below).
- **`DUMP`** was declared to test whether it opened a real per-app battery-attribution path
  when combined with `PACKAGE_USAGE_STATS`. It did, but the same adb-only ceiling applies, so
  it is gone too, and nothing in the shipped app depends on it having ever been declared.

Removing both took an on-device adb client and a root shell out of the app entirely — neither
transport nor anything that consumed their output remains. This is not a degraded state the
app works around; it is the app's permanent, only state now, for every user.

**State of health's exception:** AOSP checks a platform "state of health is public" flag
before enforcing `BATTERY_STATS` on that one specific property. On a build/device where that
flag is set, this app reads it with no permission at all (`FrameworkStateOfHealth`) — a
genuine, unprivileged public-API read, not a leftover from the removed permission.

## Sensitive-permission summary

The app requests no runtime permissions in the dangerous group — no location, contacts,
camera, microphone, storage, or phone. `POST_NOTIFICATIONS` is requested to show the sampling
foreground-service notification.

## Points a reviewer may raise

**"Does the app instruct users to enable ADB debugging, root their device, or run a shell
command?"** No, and it never has an active code path that could: there is no adb client, no
root shell, and no permission declared that only a computer could grant. Every permission this
app declares is either install-time (granted automatically) or reachable entirely from within
the app or the system's own Settings app.

**"Why does a battery app need INTERNET?"** It doesn't, and neither flavour declares it.
