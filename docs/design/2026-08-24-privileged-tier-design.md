# Privileged tier without Shizuku — design

Date: 2026-08-24
Status: approved design, not yet planned
Sub-project: **SP1** of 4 (see "Programme context")

## Problem

The privileged tier of this app currently depends on [Shizuku](https://github.com/RikkaApps/Shizuku):
a third-party Gradle dependency (`dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`) **and**
a separate app the user must install and start. Both are unacceptable going forward: the app is
to have no third-party dependencies and no external app requirement.

Shizuku's mechanism is not magic. It runs a Java process via `app_process` under the shell UID
(2000) or root, obtained through ADB, and hands the resulting binder to client apps. Everything
it does is reachable by an app that speaks the ADB protocol itself.

## Goals

- Delete the Rikka dependency and the Shizuku app requirement entirely.
- Preserve today's privileged capability: `dumpsys battery` and `dumpsys batterystats --checkin`.
- Preserve the graceful-degradation property: losing the privileged transport mid-session flips
  every privileged `Reading` to a "needs privileged access" reason on the repository's next
  emission, with no crash and no stale data.
- Keep `minSdk 26`. The unprivileged tier already spans API 26–37 and must keep doing so.
- Keep the shell surface fenced to a fixed allowlist.

## Non-goals (SP1)

- Zero-PC setup on Android 11+. That needs SPAKE2 + TLS-PSK and is **SP3**, gated behind a spike.
- Onboarding UX for enabling ADB — **SP2**.
- README truth-up, R8 verification, Play declarations, widened metrics — **SP4**.

## Programme context

| | Sub-project | Depends on |
|---|---|---|
| **SP1** | Privileged tier, PC-assisted (this doc) | — |
| SP2 | Onboarding UX for enabling ADB | SP1's state machine |
| SP3 | Zero-PC pairing on Android 11+ (SPAKE2 + TLS 1.3) | SP1; gated behind a feasibility spike |
| SP4 | Docs, release readiness, widened metrics | SP1 |

SP1 alone delivers the actual requirement: no external app, no third-party dependency, working
on API 26 through 37. Its cost is one `adb tcpip 5555` from a computer per boot for non-rooted
users; rooted users pay nothing.

## Decisions taken

| Decision | Choice | Rationale |
|---|---|---|
| Dependency scope | Third-party only | AndroidX/Google (Compose, Room, WorkManager, DataStore, Hilt) stay. They ship with the toolchain and require nothing of the user. |
| Enablement ordering | Android 11+ pairing → legacy `tcpip` → root, phased | Only the pairing step needs invented crypto. Everything else uses platform primitives. Risk goes last so it never blocks shipping. |
| Shell surface | Fixed allowlist | Two compile-time command constants. No arbitrary exec, nothing data-derived reaches a shell. |
| Transport mechanism | Direct shell exec (approach A) | See below. |

### Why direct shell exec, not an `app_process` server

Rejected alternative: replicate Shizuku's architecture — launch `app_process` with the APK on the
classpath through the ADB shell, have it host `PrivilegedBatteryService`, and publish its binder
back to the app.

That preserves 219 lines already written and tested, and binder calls beat shell round-trips. But
the binder hand-off from a shell-UID process back into the app is the fiddliest part of Shizuku,
and re-deriving it buys capability that a two-command allowlist never uses. Shell exec also
unifies the root and ADB paths behind one interface.

Accepted cost: `PrivilegedBatteryService.kt` and `IUserService.aidl` are deleted, not reused. If
SP4 ever needs real system-API calls rather than text dumps, the `PrivilegedShell` seam lets a
binder-backed implementation slot in underneath without touching `AdbGateway`, `BatteryRepository`,
or any ViewModel.

---

## §1 Transport and authentication

### Component layout

Two layers. `data/privileged/` keeps the app-facing seam; `data/privileged/adb/` holds the wire
protocol and nothing else.

```
data/privileged/
├── PrivilegedBatterySource.kt   (existing, interface — see §2 for its diff)
├── PrivilegedAvailability.kt    (renamed from ShizukuAvailability.kt)
├── PrivilegedShell.kt           NEW — the transport seam
├── AdbGateway.kt                NEW — implements PrivilegedBatterySource
├── RootShell.kt                 NEW — PrivilegedShell over `su -c`
├── DumpsysBatteryParser.kt      (existing, untouched)
├── BatteryStatsCheckinParser.kt (existing, untouched)
└── adb/
    ├── AdbMessage.kt
    ├── AdbKeyPair.kt
    ├── AdbConnection.kt
    ├── AdbStream.kt
    └── AdbShell.kt              PrivilegedShell over AdbConnection
```

| File | Responsibility |
|---|---|
| `PrivilegedShell.kt` | The seam both transports implement: `runDump(): String?`, `runCheckin(): String?`, plus an observable `TransportState`. Two methods, mirroring the two allowlisted commands — this is where the allowlist is structurally enforced, since there is no method that takes a command. |
| `AdbGateway.kt` | `PrivilegedBatterySource` implementation. Owns one `RootShell` and one `AdbShell`, reduces their two `TransportState`s into `PrivilegedAvailability` (§2), and routes `dumpBattery()`/`dumpBatteryStatsCheckin()` to whichever transport is `Ready`. The only stateful, impure component. |
| `RootShell.kt` | `PrivilegedShell` over `exec("su", "-c", …)`. See §3. |
| `AdbMessage.kt` | Wire framing: 24-byte header, six little-endian `uint32` — `command, arg0, arg1, length, crc32, magic` where `magic == command xor 0xFFFFFFFF`. Encode, decode, validate. Pure. |
| `AdbKeyPair.kt` | RSA-2048 keypair; `sign(token)`; `androidPublicKey()`. |
| `AdbConnection.kt` | Socket, handshake, stream multiplexing, lifecycle. |
| `AdbStream.kt` | One logical stream: `OPEN` → `OKAY` → `WRTE`* → `CLSE`; read to EOF. |
| `AdbShell.kt` | `PrivilegedShell` over `AdbConnection`. Two constant command strings live here. |

Nothing above `AdbGateway` knows a transport exists. `BatteryRepository` and every ViewModel
continue to depend only on `PrivilegedBatterySource`, exactly as they depend on it today.

### Handshake

1. Client sends `CNXN(version=0x01000000, maxdata=256K, "host::features=...")`.
2. Device replies `AUTH(TOKEN, 20 random bytes)`.
3. Client replies `AUTH(SIGNATURE, sign(token))`.
4. If the device does not know the key it re-sends `TOKEN`; client replies
   `AUTH(RSAPUBLICKEY, ...)`, which raises the on-screen **"Allow USB debugging?"** prompt.
   The user taps once, ever.
5. Device confirms with its own `CNXN`.

### The two pieces of genuine protocol reverse-engineering

**Signature format.** adb signs the raw token with PKCS#1 v1.5 under a SHA-1 `DigestInfo`, but
the token *is already* the digest. So prepend the 15-byte ASN.1 prefix
`30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14` to the token and sign with `NONEwithRSA`.
Signing with `SHA1withRSA` would double-hash and fail authentication.

**Public key encoding.** Not PEM, not X.509 — adb's own 524-byte `ANDROID_PUBKEY` struct,
little-endian:

```
uint32  modulus_size_words     // 64 for RSA-2048
uint32  n0inv                  // -1 / n[0] mod 2^32
uint32  modulus[64]            // little-endian 32-bit words
uint32  rr[64]                 // R^2 mod n, R = 2^2048
uint32  exponent               // 65537
```

Base64-encoded, with ` user@host` appended. `n0inv` and `rr` are computed with `BigInteger`.
Pure arithmetic, fully unit-testable against a real desktop `adbkey.pub`.

### Deliberately not advertising `shell_v2`

Shell protocol v1 merges stdout and stderr into a raw byte stream with no exit code — exactly what
`DumpsysBatteryParser` and `BatteryStatsCheckinParser` already consume. v2's packetized framing
would be more code producing information the parsers discard.

### Key storage

AndroidKeystore, `DIGEST_NONE` + `SIGNATURE_PADDING_RSA_PKCS1`, non-exportable private key.

**Risk:** some OEM Keystore implementations may reject `NONEwithRSA`. Fallback is an app-private
key file, which is no weaker than desktop adb's own plaintext `adbkey`. Resolve by spike during
implementation; do not assume.

### Port

5555 by default, user-overridable and persisted in `SettingsStore`. Discovery is a connect attempt
to `127.0.0.1:<port>` — nothing more elaborate is needed for the legacy `tcpip` transport, whose
port the user chooses when they run `adb tcpip`.

---

## §2 Gateway state machine

`ShizukuAvailability` becomes `PrivilegedAvailability`. Same discipline as today — a pure reducer
over independently observed facts, JVM-testable with no device — but there are now two transports.

```kotlin
enum class Transport { Root, Adb }

sealed interface TransportState {
    data object Unavailable : TransportState           // no su binary / adbd not listening
    data object AwaitingAuthorization : TransportState // prompt on screen right now
    data object Denied : TransportState                // user said no
    data object Connecting : TransportState
    data object Ready : TransportState
}

sealed interface PrivilegedAvailability {
    data object Unavailable : PrivilegedAvailability
    data object AwaitingAuthorization : PrivilegedAvailability
    data object Denied : PrivilegedAvailability
    data object Connecting : PrivilegedAvailability
    data class Ready(val via: Transport) : PrivilegedAvailability
}

fun privilegedAvailability(root: TransportState, adb: TransportState): PrivilegedAvailability
```

The reducer prefers **root when both are `Ready`**: root survives reboot with no setup, ADB does
not. `Ready` carries `via` so the UI can distinguish "connected via root" from "via ADB" — the two
have different reboot stories the user needs told.

Splitting per-transport rather than keeping one flat boolean table is a deliberate departure from
the current four-boolean function. With two independent transports a flat table needs 25 rows and
stops being readable; two small states reduced by one function stays readable, and each transport
is testable alone.

### Interface diff on `PrivilegedBatterySource`

| Member | Change |
|---|---|
| `dumpBattery()` | unchanged |
| `dumpBatteryStatsCheckin()` | unchanged |
| `state` | type renamed only |
| `requestPermission()` | → `connect()`. Semantics shift from "show Shizuku's prompt" to "attempt the transport"; the Allow dialog is a side effect. |
| `refresh()` | unchanged role — re-probe on resume |

`BatteryRepository`, `HealthViewModel` and `AppsViewModel` therefore change by a rename plus one
method name. The Compose layer is untouched apart from `UnlockCard`'s copy.

### Preserved property

Today, Shizuku dying mid-session flips `state` to `NotRunning` live and every privileged `Reading`
degrades on the repository's next emission, with no crash and no stale data. The same holds here:
socket EOF or a read failure flips the ADB transport to `Unavailable`, and
`Reading.NeedsPrivilegedAccess` does the job `NeedsShizuku` does now. Reboot is simply that path
firing — adbd stops listening, the next command fails, `UnlockCard` explains the re-enable.

Lifetime: `@Singleton`, process-lifetime, as `ShizukuGateway` is today.

---

## §3 Root path and error handling

### Root detection is a probe, not a file check

`/system/bin/su` existing proves nothing (it may not grant) and its absence proves nothing (Magisk
relocates). Detection *is* execution: spawn `su`, run a trivial probe, see if it returns.

Consequence: **probing raises the Magisk grant dialog.** Doing that at app launch is hostile — a
battery app has no business asking for root before the user has asked for anything. Therefore:

- Never probe on startup.
- Probe when the user taps connect, or when a persisted "root previously granted" flag in
  `SettingsStore` says the probe is free.
- While the dialog is up, root's `TransportState` is `AwaitingAuthorization` — the same state the
  ADB transport uses for "Allow USB debugging?", which is why §2 shares it.

### Execution

`exec(arrayOf("su", "-c", COMMAND))`, one process per call, `destroy()` on timeout.

Spawn-per-call rather than a long-lived `su` with a stdin pipe: dumps are infrequent, and
per-process makes timeouts trivially enforceable instead of requiring stream framing to know where
one command's output ends. Commands are compile-time constants, so `-c` carries no injection
surface.

### Timeouts — two layers, values carried over unchanged

| Layer | Dump | Checkin | Implementation |
|---|---|---|---|
| Client-side | 7s | 15s | `withGatewayDumpTimeout` / `withGatewayCheckinTimeout`, unchanged |
| Transport-side | 3s | 8s | `Socket.setSoTimeout` (ADB) / `Process.destroy()` (root) |

The existing rationale for the split holds identically for sockets and pipes: the outer bound
catches a wedged transaction the inner one cannot reach, and checkin needs more headroom because
it marshals roughly 525KB. Nothing to re-derive; only the inner bound changes home, having
previously lived in `PrivilegedBatteryService`.

### Failure collapses to null

Both dump methods return `null` on timeout, EOF, socket death, denied root, or a malformed frame.
`BatteryRepository` already treats every null identically. Nothing throws across the interface
boundary.

### No hot reconnect loop

On transport failure: flip to `Unavailable` and stop. Retry happens on `refresh()`, which the
Health screen already calls on every `ON_RESUME`. A background reconnect loop draining battery
inside a battery-health app is the one failure mode worth designing hard against.

### Carry-over guards

- **One in-flight connect at a time** (`AtomicBoolean`). This is exactly the bug the current
  `bindInFlight` fixed, where repeated `ON_RESUME` orphaned connection objects — same shape,
  same reason.
- **Flow control.** Every `A_WRTE` received must be acknowledged with `A_OKAY` or the device
  stalls mid-dump. A classic ADB-client bug; named here so it lands in the plan as a test.
- **Payload cap.** Checkin streams into a buffer with a hard 4MB ceiling. Past it, abort and
  return `null` rather than OOM on a pathological device.

---

## §4 Change inventory

### Deleted — 503 lines

| File | Lines | Why |
|---|---|---|
| `data/privileged/ShizukuGateway.kt` | 284 | Replaced by `AdbGateway` |
| `data/privileged/PrivilegedBatteryService.kt` | 185 | No server process in approach A |
| `aidl/.../IUserService.aidl` | 34 | No binder surface |

### Renamed

- `ShizukuAvailability.kt` → `PrivilegedAvailability.kt`; `shizukuAvailability()` →
  `privilegedAvailability()`; `SHIZUKU_PACKAGE_NAME` deleted outright.
- `Reading.NeedsShizuku` → `Reading.NeedsPrivilegedAccess`. Reaches `domain/Reading.kt`,
  `domain/BatteryModels.kt`, `ui/components/ReadingSlot.kt`, `ui/health/HealthUiState.kt`,
  `ui/apps/AppsUiState.kt`, `data/repo/CycleCountResolver.kt`,
  `data/framework/BatteryProperty.kt`, `play/.../PlayAppLabelResolver.kt` and four test files.
- `ShizukuGatewayTimeoutTest` / `ShizukuGatewayCheckinTimeoutTest` → `AdbGateway*`. The timeout
  functions under test are unchanged, so these are rename-only.

### Modified (neither deleted nor renamed)

- `data/privileged/PrivilegedBatterySource.kt` — `requestPermission()` becomes `connect()`;
  `state`'s type changes; documentation rewritten (it currently explains Shizuku's static API).
- `di/PrivilegedModule.kt` — binds `AdbGateway` instead of `ShizukuGateway`.
- `data/repo/BatteryRepository.kt`, `ui/health/HealthViewModel.kt`, `ui/apps/AppsViewModel.kt` —
  rename plus the one method name.
- `ui/components/UnlockCard.kt`, `ui/health/HealthScreen.kt`, `ui/apps/AppsScreen.kt` — new states
  and new user-facing copy.
- `data/settings/SettingsStore.kt` — two new preferences: the ADB port (default 5555) and the
  "root previously granted" flag §3 relies on.

**Total: 34 source and test files, plus `build.gradle.kts` and `gradle/libs.versions.toml`.**

### Manifest — removals

```
moe.shizuku.manager.permission.API_V23              (uses-permission)
<queries><package moe.shizuku.privileged.api />      (queries)
rikka.shizuku.ShizukuProvider                        (provider)
```

### Manifest — addition, and it needs disclosure

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Android enforces `INTERNET` at socket creation through the `inet` group; **loopback is not
exempt.** So an app that today declares no network permission at all begins declaring one. Users
read that list, and so do reviewers.

Treat this as a first-class disclosure, not a line item. The README and store listing should state
plainly that `INTERNET` exists solely to reach the on-device ADB daemon on `127.0.0.1`, and that
the app makes no outbound network requests. That claim should also be enforced in CI — a test
asserting no non-loopback socket is ever opened makes it checkable rather than merely asserted,
which matches this codebase's existing habit of proving its constraints instead of stating them.

### Build files

- `gradle/libs.versions.toml`: drop `shizuku`, `shizuku-api`, `shizuku-provider`, and Rikka's
  Maven repository entry if present.
- `app/build.gradle.kts`: drop both dependencies; drop `buildFeatures { aidl = true }` if AIDL
  becomes unused.
- `app/src/main/keepRules/rules.keep`: currently keeps Shizuku's reflection targets. Approach A
  instantiates nothing by reflection, so this likely empties out — verify against an R8 release
  build rather than assume.

---

## §5 Testing strategy

### 1. Pure JVM — arithmetic and reducer

- `AdbMessage`: encode/decode round-trip, CRC, `magic` validation, truncated and malformed frames.
- **`androidPublicKey()` against golden vectors.** Take a real desktop `adbkey`/`adbkey.pub` pair
  and assert the 524-byte struct and its base64 match byte-for-byte. The highest-value test in
  SP1: the `n0inv`/`rr` arithmetic is the most likely thing to be subtly wrong, and it is fully
  deterministic with no device.
- Signature shape: assert the signed blob equals ASN.1 prefix ‖ token, verifiable with the public
  key.
- `privilegedAvailability()`: full cross product of both transport states, matching the discipline
  of the existing `ShizukuAvailabilityTest`.
- Timeout functions: rename-only, virtual clock under `kotlinx-coroutines-test`.
- Parser tests untouched — the `dumpsys-battery-sm-a356e.txt` and
  `batterystats-checkin-sm-a356e.csv` fixtures already exist and their inputs do not change.

### 2. Fake ADB daemon on the JVM

A test-only `ServerSocket` that genuinely speaks the protocol: answers `CNXN`, issues
`AUTH TOKEN`, verifies the signature, serves canned dump bytes on `shell:` opens. This makes
`AdbConnection` and `AdbStream` testable end-to-end with no device:

- unknown-key path → `RSAPUBLICKEY` → accept
- **flow control** — emit the dump across many `WRTE` chunks and assert every one is `OKAY`'d,
  catching the §3 stall bug mechanically
- EOF mid-dump → `null`
- read timeout → `null`
- oversized payload → abort at the 4MB cap, `null`, no OOM

### 3. Instrumented, on device

- `AdbKeyPair` against real AndroidKeystore — §1's flagged risk. It can only fail on hardware.
- `AdbGateway` transitions against a fake `PrivilegedShell`.
- `BatteryRepositoryTest` already injects a fake `PrivilegedBatterySource`; carries over with
  renamed states.
- `UnlockCardTest`, `ReadingSlotTest`, `AppsScreenTest` updated for new states and copy.

**Gotcha:** instrumented tests run *over adb*. A test that calls `adb tcpip` or otherwise restarts
adbd would cut the harness's own transport out from under itself. Instrumented tests must never
touch real adbd — fakes only. Real transport verification is manual.

Existing traps that still apply: `connectedPlayDebugAndroidTest` takes
`-Pandroid.testInstrumentationRunnerArguments.class=` rather than `--tests`, and it uninstalls the
app when it finishes.

### 4. Manual device verification — Samsung Galaxy A35 5G (SM-A356E), Android 16

- `adb tcpip 5555` → app connects → Allow prompt → `Ready(Adb)` → real dumps parse.
- Reboot → confirm live degrade to `Unavailable`, no crash, readings show
  `NeedsPrivilegedAccess`.
- Root path verified only if a rooted device is available. If not, it ships **documented as
  unverified**, not claimed.

### Order of work

Fake daemon and golden vectors first, implementation against them.

---

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Keystore rejects `NONEwithRSA` on some OEM | Auth cannot sign | Spike early; fallback to app-private key file |
| `n0inv`/`rr` arithmetic subtly wrong | Auth silently rejected | Golden-vector test against real `adbkey.pub` |
| `A_WRTE` not acked | Dump stalls mid-stream | Fake-daemon chunked-write test |
| `INTERNET` permission perceived as network access | User trust, store review | Explicit disclosure in README and listing; CI test asserting loopback-only |
| adbd not listening after reboot | Tier unavailable until re-enabled | Honest `UnlockCard` copy; SP2 owns the guided flow |
| Root probe raises Magisk dialog unprompted | Hostile first-run | Never probe at startup; only on explicit connect or persisted grant |

## Out of scope

README truth-up (its "screens not written yet" and "R8 disabled" claims are both stale), R8
verification, Play `specialUse` foreground-service declaration, store listing copy, and widened
`dumpsys` metrics. All SP4. Left untouched here rather than half-fixed.
