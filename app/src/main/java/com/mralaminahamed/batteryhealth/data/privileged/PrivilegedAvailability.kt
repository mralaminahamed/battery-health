package com.mralaminahamed.batteryhealth.data.privileged

/**
 * Which privileged transport a reading actually came through. Surfaced because the two
 * have different reboot stories the user needs told: root survives one, adb does not.
 */
enum class Transport { Root, Adb }

sealed interface TransportState {
    /**
     * The transport is not available to this app. Root requires device rooting with Magisk;
     * ADB requires the device to have wireless debugging enabled or to be paired over USB.
     */
    data object Unavailable : TransportState

    /**
     * The transport is detected and authorized, but permission grant is pending. Root shows
     * a prompt on the device itself; ADB's prompt is built into the connection handshake.
     */
    data object AwaitingAuthorization : TransportState

    /**
     * User refused the transport's authorization request. Root: user tapped deny on device.
     * ADB: user rejected the pairing prompt. The user can retry; offer a button to try again.
     */
    data object Denied : TransportState

    /**
     * Authorization complete; the transport is now binding or handshaking. Brief and real
     * (under a second), but the read this moment issues will fail; nothing the user can do.
     */
    data object Connecting : TransportState

    /**
     * The transport is alive and can be queried right now.
     */
    data object Ready : TransportState
}

sealed interface PrivilegedAvailability {
    /**
     * Neither transport is available. Show the user setup instructions for root or ADB,
     * depending on their device configuration.
     */
    data object Unavailable : PrivilegedAvailability

    /**
     * A prompt is currently on screen asking for permission. User should tap allow to
     * proceed. Different prompt for each transport: device alert for root, wireless-pairing
     * dialog for ADB.
     */
    data object AwaitingAuthorization : PrivilegedAvailability

    /**
     * User tapped deny and the authorization was refused. Offer a button to retry; the
     * user can still change their mind and grant permission.
     */
    data object Denied : PrivilegedAvailability

    /**
     * A transport is binding or handshaking. Transient state lasting under a second.
     * Reads issued right now will fail; user should wait.
     */
    data object Connecting : PrivilegedAvailability

    /**
     * A transport is alive and ready. The [via] field tells the UI which one, because
     * they have different reboot stories: root survives, ADB does not. Story the user
     * must hear if they need to reboot.
     */
    data class Ready(val via: Transport) : PrivilegedAvailability
}

/**
 * Reduces two independently-observed transports into the single state the UI renders.
 *
 * Kept pure and separate from `AdbGateway` -- which is the impure part, holding sockets
 * and processes -- so this precedence is JVM-testable without a device, an emulator or
 * Robolectric.
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
    // Boolean-condition when for readability over a 2×5 matrix. If a sixth TransportState
    // is added, this function will not be flagged by the compiler; the new state falls to
    // else -> Unavailable. Revisit and handle it explicitly if that ever happens.
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
