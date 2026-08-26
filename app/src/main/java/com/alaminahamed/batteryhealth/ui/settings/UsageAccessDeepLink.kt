package com.alaminahamed.batteryhealth.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Deep-links to the system's Usage access list, the only route to `PACKAGE_USAGE_STATS`:
 * it is appop-gated with no runtime dialog of its own. Shared by [SettingsScreen] (the
 * Permissions section's own row) and `AppsScreen` (the per-app drain estimate's own
 * disclosure card) -- both send the user to exactly the same system screen for exactly the
 * same permission, so there is exactly one place that decides how.
 *
 * `resolveActivity` is checked first because some OEM builds ship no activity for
 * `ACTION_USAGE_ACCESS_SETTINGS` at all -- `startActivity` on an unresolvable intent
 * throws `ActivityNotFoundException`, and there is no fallback screen worth sending the
 * user to instead, so this silently does nothing rather than crash the caller over a deep
 * link with no working destination.
 */
fun openUsageAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
