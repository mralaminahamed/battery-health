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

## About the Internet permission

The app declares `android.permission.INTERNET`, and it is reasonable to want an explanation
for that in an app claiming to send nothing.

It is required to open a network socket to `127.0.0.1` — your device talking to itself.
Android enforces the `INTERNET` permission at socket creation regardless of destination, and
loopback is not exempt, so an app cannot connect to its own device without holding it.

The app uses this to reach the Android Debug Bridge daemon running on your own phone, which is
how it reads the battery metrics Android does not expose to ordinary apps. Those bytes never
leave the handset.

This is enforced mechanically, not merely promised: an automated test fails the build if any
part of the app constructs a network socket that could reach an address other than loopback.

## About the privileged tier

Some battery metrics — state of health, first-use date, manufacturer data — are protected by
`BATTERY_STATS`, a permission Android grants only to system applications.

If you choose to enable it, the app reads those values by running two fixed commands,
`dumpsys battery` and `dumpsys batterystats --checkin`, through the debug bridge or through
`su` on a rooted device. Those two commands are the entire privileged surface: they are
compiled into the app as constants, and there is no code path by which any other command can
be run.

Enabling this is entirely optional. The app works without it and simply reports the affected
metrics as unavailable.

To authenticate to the debug bridge, the app generates an RSA key inside the Android Keystore.
The key is marked non-exportable, so it cannot be read out of the device's hardware-backed
keystore by the app or by anything else, and it is used for nothing but this local connection.

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

Questions about this policy: **n.mukto@codexpert.io**

Source code: https://github.com/mralaminahamed/battery-health
