# Privacy Policy — Battery Health

**Last updated: 25 August 2026**

Battery Health does not collect, transmit, or share your personal data. Everything the
app records stays on your device.

That is not a summary of a longer, more qualified answer further down. It is the whole answer,
and the rest of this document exists to be specific about it.

## What the app stores, and where

All of it is written to the app's own private storage on your device — a local database and a
local settings file that no other app can read. None of it is uploaded anywhere.

**Battery samples**

- Timestamp
- Charge level (percent)
- Charge counter (µAh)
- Instantaneous current (µA)
- Voltage (mV)
- Temperature (tenths of °C)
- Charging status code

**Charge and discharge sessions**

- Start and end time, start and end charge level
- Start and end charge counter readings
- Peak temperature, average power
- Screen-on duration during the session

**Your settings**

- Design-capacity override, if you set one
- Whether background recording is enabled
- Preferred current units
- The ADB port the privileged tier connects to
- Whether root access was previously granted

## What the app does not do

- **No network transmission.** The app makes no outbound network requests of any kind. There
  is no server, no analytics, no crash reporting, and no advertising.
- **No third-party SDKs.** The app depends only on Google's own AndroidX libraries. Nothing
  from a data broker, analytics vendor, or ad network is present.
- **No accounts.** There is no sign-in, and the app never asks who you are.
- **No advertising identifier.** The app does not read or use one.

## About permissions

The app on Google Play declares **no Internet permission at all**. It cannot open a network
socket, to any address, even in principle — the code that once did so is not compiled into
that build.

A separate build distributed outside Google Play does declare `android.permission.INTERNET`,
and only for one purpose: opening a socket to `127.0.0.1`, your device talking to itself.
Android enforces that permission at socket creation regardless of destination, and loopback is
not exempt, so an app cannot connect to its own device without holding it. No bytes leave the
handset either way.

That separation is mechanical rather than promised. An automated test fails the build if any
production source constructs a network socket that could reach an address other than loopback.

### `BATTERY_STATS`

The app declares this permission and is **not granted it** on a normal install. Android
reserves it for system applications, and no user action inside the app can obtain it.

It can be granted deliberately by the device's owner, from their own computer, with a single
command. Ungranted, the declaration does nothing and the app behaves exactly as it would
without it, reporting the affected values as unavailable.

Where the permission is granted, the app reads state of health, first-use date and
manufacturing date directly from Android's own `BatteryManager`. Nothing read this way leaves
the device. The battery's serial number is deliberately **not** recorded even into the app's
own diagnostic report, because that report is meant to be shareable and a serial identifies
one physical device.

## About the privileged tier

**The Google Play build has none.** It contains no debug-bridge client, no `su` handling, and
no network permission. Everything it shows comes from ordinary Android APIs, from settings the
manufacturer publishes to any app, or from measurements the app takes itself.

A separate build distributed outside Google Play carries an optional tier for two values that
have no public API at all: the manufacturer's own accumulated cycle count and its second
health figure. Where a user enables it, that build reads them by running two fixed commands,
`dumpsys battery` and `dumpsys batterystats --checkin`, through the debug bridge or through
`su` on a rooted device. Those two commands are the entire privileged surface: they are
compiled in as constants, and there is no code path by which any other command can be run.

To authenticate to the debug bridge, that build generates an RSA key inside the Android
Keystore. The key is marked non-exportable, so it cannot be read out of the device's
hardware-backed keystore by the app or by anything else, and it is used for nothing but this
local connection.

## Per-app battery attribution

One distribution of the app (the "full" build, published outside Google Play) declares
`QUERY_ALL_PACKAGES` in order to display names and icons alongside per-app battery usage. It
uses this only to turn a package name into something human-readable on screen. The list of
your installed applications is never recorded, transmitted, or retained.

The Google Play build does not declare this permission.

## Your data, your control

The app's data lives in its private storage, so uninstalling it deletes everything permanently.
You can also clear it at any time from Android Settings → Apps → Battery Health →
Storage → Clear data.

Because nothing is uploaded, there is no copy anywhere else to request, correct, or delete.

## Children

The app is not directed at children, collects no personal information from anyone, and has no
sign-in or user-generated content.

## Changes to this policy

If this policy changes, the revised version will be published here and the date at the top
updated. If a future version of the app ever collected or transmitted data — which is not
planned — that change would be described here before it shipped.

## Contact

Questions about this policy: **alamin.ahamed.dev@gmail.com**

Source code: https://github.com/mralaminahamed/battery-health
