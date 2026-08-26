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
)
