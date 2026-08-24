package com.mralaminahamed.batteryhealth.data.privileged

/**
 * Which privileged transport a reading actually came through. Surfaced because the two
 * have different reboot stories the user needs told: root survives one, adb does not.
 */
enum class Transport { Root, Adb }

sealed interface TransportState {
    data object Unavailable : TransportState
    data object AwaitingAuthorization : TransportState
    data object Denied : TransportState
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

/**
 * Reduces two independently-observed transports into the single state the UI renders.
 *
 * Kept pure and separate from `AdbGateway` -- which is the impure part, holding sockets
 * and processes -- so this precedence is JVM-testable without a device, an emulator or
 * Robolectric, exactly as `shizukuAvailability` was before it.
 *
 * Precedence is by what the user can act on, most actionable first: a working transport
 * beats a pending one; a prompt currently on screen beats a refusal already given; a
 * refusal the user can still reverse beats nothing at all. Root outranks adb at equal
 * rank because it needs no per-boot setup.
 */
fun privilegedAvailability(
    root: TransportState,
    adb: TransportState,
): PrivilegedAvailability = when {
    root == TransportState.Ready -> PrivilegedAvailability.Ready(Transport.Root)
    adb == TransportState.Ready -> PrivilegedAvailability.Ready(Transport.Adb)
    root == TransportState.AwaitingAuthorization || adb == TransportState.AwaitingAuthorization ->
        PrivilegedAvailability.AwaitingAuthorization
    root == TransportState.Connecting || adb == TransportState.Connecting ->
        PrivilegedAvailability.Connecting
    root == TransportState.Denied || adb == TransportState.Denied ->
        PrivilegedAvailability.Denied
    else -> PrivilegedAvailability.Unavailable
}
