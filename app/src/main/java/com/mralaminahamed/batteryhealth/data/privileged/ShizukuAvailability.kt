package com.mralaminahamed.batteryhealth.data.privileged

/** The `applicationId` of the Shizuku app itself -- a separate app the user installs and
 * starts, never bundled with this one. Used both to detect whether it is installed and
 * to build the launch intent [UnlockCard]'s "open Shizuku" action uses. */
const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

/**
 * Every state the privileged tier can actually be in, kept distinct on purpose rather
 * than collapsed into one "is Shizuku available" boolean -- a single flag cannot tell the
 * user what to do next, and what to do next is different for each of these:
 *
 * - [NotInstalled]: install the separate Shizuku app.
 * - [NotRunning]: installed, but not started this boot -- start it via wireless
 *   debugging or ADB (Shizuku's own UI walks through both).
 * - [PermissionNotGranted]: running, but this app has not asked, or asked and was
 *   refused -- request the permission.
 * - [Connecting]: permission granted and the binder is alive; the `UserService` bind
 *   this app just issued has not completed yet. Real, but brief (well under a second on
 *   the device this was verified against) -- nothing the user needs to act on.
 * - [Bound]: the privileged tier can be queried right now.
 */
sealed interface ShizukuAvailability {
    data object NotInstalled : ShizukuAvailability
    data object NotRunning : ShizukuAvailability
    data object PermissionNotGranted : ShizukuAvailability
    data object Connecting : ShizukuAvailability
    data object Bound : ShizukuAvailability
}

/**
 * The pure decision table [ShizukuGateway] drives from four independent, individually
 * observable facts. Kept separate from the gateway itself (which is the only impure part
 * -- it talks to Shizuku's static API and Android's `PackageManager`) so this ordering is
 * JVM-testable without a device, an emulator, or Robolectric.
 *
 * Order matters and is deliberately checked most-fundamental-first: a package that is not
 * installed cannot have a live binder, a dead binder cannot have a granted permission
 * worth reporting, and an ungranted permission cannot have a bound service -- each
 * earlier check short-circuits a later fact that would otherwise be meaningless or stale.
 */
fun shizukuAvailability(
    packageInstalled: Boolean,
    binderAlive: Boolean,
    permissionGranted: Boolean,
    serviceBound: Boolean,
): ShizukuAvailability = when {
    !packageInstalled -> ShizukuAvailability.NotInstalled
    !binderAlive -> ShizukuAvailability.NotRunning
    !permissionGranted -> ShizukuAvailability.PermissionNotGranted
    !serviceBound -> ShizukuAvailability.Connecting
    else -> ShizukuAvailability.Bound
}
