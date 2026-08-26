# Privacy Policy — Battery Health

**Last updated: 26 August 2026**

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

- Whether background recording is enabled
- Preferred current units
- Your chosen appearance (One UI or Material)

## What the app does not do

- **No network transmission.** The app makes no outbound network requests of any kind. There
  is no server, no analytics, no crash reporting, and no advertising.
- **No third-party SDKs.** The app depends only on Google's own AndroidX libraries. Nothing
  from a data broker, analytics vendor, or ad network is present.
- **No accounts.** There is no sign-in, and the app never asks who you are.
- **No advertising identifier.** The app does not read or use one.

## About permissions

**Neither build the app ships declares an Internet permission.** The app cannot open a
network socket, to any address, even in principle. An earlier version of this app carried an
optional on-device component that opened a loopback socket to reach data gated behind a
system-level permission; that component has been removed entirely, along with the permission
declaration it needed.

Every permission the app declares today is one you can grant, deny, or leave alone entirely
from within the app or from Android's own Settings app — never from a computer, and never by
enabling developer options or USB debugging:

- **Notifications** (`POST_NOTIFICATIONS`) — asked through a normal system dialog, only if you
  turn on background charge-session recording. It shows the notification that recording is
  running; nothing else.
- **Usage access** (`PACKAGE_USAGE_STATS`) — off by default. You can turn it on from Android
  Settings → Apps → Special access → Usage access, which the app can open for you, or leave it
  off; the app works either way.
- A handful of other permissions (background service, restarting sampling after your phone
  reboots, and — on the build distributed outside Google Play — reading installed app names to
  label the per-app CPU-time list) are granted automatically at install and need no action from
  you.

The Settings screen inside the app lists every permission it declares, its current state, and
the one thing (if anything) that would change it.

### State of health, first-use date and manufacturing date

Android reserves these behind a system-level permission (`BATTERY_STATS`) that this app cannot
be granted through a normal install, on any device, by any action you or the app can take. The
app does not declare this permission at all — declaring one it can never hold would be
pointless — so these two dates are simply reported as unavailable. State of health has one
narrow exception: on some devices, the platform itself makes that one figure available to any
app with no permission at all, and where that is true, this app reads and shows it.

Nothing this app reads from any Android API leaves your device. The battery's serial number is
deliberately **not** recorded even into the app's own diagnostic report, because that report
is meant to be shareable and a serial identifies one physical device.

## Per-app CPU time

The build distributed outside Google Play declares `QUERY_ALL_PACKAGES` in order to display
names and icons alongside the Apps screen's per-uid CPU-time list. It uses this only to turn a
package name into something human-readable on screen. The list of your installed applications
is never recorded, transmitted, or retained.

The Google Play build does not declare this permission, and its own CPU-time list is limited
to apps Android already makes visible to any app by default.

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
