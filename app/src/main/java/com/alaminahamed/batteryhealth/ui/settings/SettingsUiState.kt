package com.alaminahamed.batteryhealth.ui.settings

import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice

data class SettingsUiState(
    /**
     * Mirrors `SettingsStore.designLanguageChoice`. Defaults to
     * [DesignLanguageChoice.Auto] only as the cold-start placeholder before the real flow
     * first emits, the same convention the other fields here use.
     */
    val designLanguage: DesignLanguageChoice = DesignLanguageChoice.Auto,
    /**
     * Whether notifications are permitted.
     *
     * Always true below API 33, where there is no such runtime permission and posting is
     * unconditional. Re-read on resume: the user can change it in system settings while
     * this screen is in the background.
     */
    val notificationsGranted: Boolean = true,
    /**
     * Every permission this app declares, each already carrying its live state -- the
     * Permissions section's whole content. Empty only as the cold-start placeholder before
     * `SettingsViewModel` first reads the platform, the same convention every other field
     * here uses; the real value always has exactly six rows -- one requestable
     * (`POST_NOTIFICATIONS`) plus five install-time.
     */
    val permissions: List<PermissionRow> = emptyList(),
)
