package com.alaminahamed.batteryhealth.ui.settings

import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice

data class SettingsUiState(
    /**
     * The value `HealthEstimator` is actually measuring against right now, and where it
     * came from -- see `DesignCapacityProvider.effective`'s own doc. Defaults to
     * [EffectiveDesignCapacity.None] only as the cold-start placeholder before the real
     * flow first emits, the same convention `HealthUiState.designCapacity` already uses.
     */
    val designCapacity: EffectiveDesignCapacity = EffectiveDesignCapacity.None,
    /**
     * Mirrors `SettingsStore.adbPort`. Defaults to 5555 -- the same default
     * `SettingsStore` itself falls back to before any value has ever been written --
     * only as the cold-start placeholder before the real flow first emits.
     */
    val adbPort: Int = 5555,
    /**
     * Mirrors `SettingsStore.designLanguageChoice`. Defaults to
     * [DesignLanguageChoice.Auto] only as the cold-start placeholder before the real flow
     * first emits, the same convention the other fields here use.
     */
    val designLanguage: DesignLanguageChoice = DesignLanguageChoice.Auto,
    /**
     * Whether this build has a privileged transport compiled in. False in the Play
     * flavour, where the ADB-port setting would configure a connection that cannot be
     * made.
     */
    val privilegedTierSupported: Boolean = true,
    /**
     * Mirrors `SettingsStore.cycleCountBaseline`. Null means the user has never supplied
     * one, which is not the same as zero -- see `CycleCountResolver`, where a supplied
     * zero is a real claim about a new battery and null is the absence of any claim.
     */
    val cycleBaseline: Int? = null,
    /**
     * Mirrors `SettingsStore.unlockCardDismissed`. Surfaced here because dismissal was
     * otherwise a one-way door: the control that sets it lives on the card being
     * dismissed, so once it is gone there is nothing left to press.
     */
    val unlockCardDismissed: Boolean = false,
    /**
     * Whether `BATTERY_STATS` is held right now.
     *
     * Read from the platform on each resume rather than stored, because it is granted
     * from outside this app entirely and nothing tells the process when that happens.
     */
    val batteryStatsGranted: Boolean = false,
    /**
     * Whether notifications are permitted.
     *
     * Always true below API 33, where there is no such runtime permission and posting is
     * unconditional. Like [batteryStatsGranted] this is re-read on resume: the user can
     * change it in system settings while this screen is in the background.
     */
    val notificationsGranted: Boolean = true,
    /**
     * Every permission this app declares, each already carrying its live state -- the
     * Permissions section's whole content. Empty only as the cold-start placeholder before
     * `SettingsViewModel` first reads the platform, the same convention every other field
     * here uses; the real value always has at least nine rows. Deliberately not read by any
     * existing section: [batteryStatsGranted] and [notificationsGranted] above stay in
     * place for "Privileged readings" and "Notifications", which say more about each of
     * those two than a single table row could.
     */
    val permissions: List<PermissionRow> = emptyList(),
)
