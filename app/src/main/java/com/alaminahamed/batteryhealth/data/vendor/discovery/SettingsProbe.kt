package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * Battery-related keys in the `Settings` provider.
 *
 * This channel was missing from the first sweep and is the one that found something. On a
 * Samsung device `Settings.Global` carries `protect_battery`, which tracks the Battery
 * Protect switch — a value this app otherwise reads only through the privileged tier.
 * Reading `Settings.Global`/`Secure`/`System` needs no permission at all (writing needs
 * `WRITE_SECURE_SETTINGS`, which this app neither has nor wants), so anything real here is
 * available with zero setup.
 *
 * ## The trap this channel sets
 *
 * `adb shell settings list global` runs as the `shell` user, which can read keys an
 * ordinary app cannot. A key being visible there is *not* evidence the app can read it,
 * and treating it as such would be how this app ends up claiming a reading it cannot
 * actually take on a user's phone. Every key here is therefore probed from inside the
 * app, through the same `ContentResolver` production would use, and recorded as
 * [ProbeOutcome.Denied] when the platform refuses.
 *
 * ## Keys are named individually, never enumerated
 *
 * The provider holds hundreds of unrelated settings and listing them all would put a
 * user's device configuration into a diagnostic report for no benefit. Only keys with a
 * documented or observed battery meaning are asked for.
 */
object SettingsProbe {

    /** Which `Settings` table a key lives in. */
    enum class Namespace { Global, Secure, System }

    /**
     * @property key the setting name.
     * @property namespace which table it lives in.
     * @property note what it is believed to mean, and how confidently. Absence of a note
     *   means the meaning is not established — the sweep still records the value, but
     *   nothing in the app may act on it until someone has actually verified it.
     */
    data class Key(val key: String, val namespace: Namespace, val note: String? = null)

    /**
     * Observed on an SM-S948B running Android 16. These are Samsung's names; other
     * vendors will not have them, and asking costs one provider read that returns null.
     *
     * `protect_battery` is the one with a confirmed meaning: it read `1` while the app's
     * privileged tier independently reported Battery Protect as on, which is two sources
     * agreeing rather than one being assumed.
     *
     * The threshold keys are deliberately *not* given a confident note. `Settings.Global`
     * reported `battery_protection_threshold=95` at a moment when the privileged
     * `dumpsys` read said the active charge limit was 80%. Those cannot both describe the
     * same quantity, so at least one of them means something other than "the limit in
     * force" — most likely these hold per-mode defaults while One UI's Basic/Adaptive/
     * Maximum selection lives elsewhere. Until that is settled by watching the keys change
     * as the mode changes, this app must not render either as a charge limit.
     */
    val keys: List<Key> = listOf(
        Key(
            "protect_battery",
            Namespace.Global,
            "Battery Protect on/off. Read 1 while the privileged tier independently " +
                "reported it on.",
        ),
        Key("battery_protection_threshold", Namespace.Global),
        Key("battery_protection_recharge_level", Namespace.Global),
        Key("battery_protection_default_value", Namespace.Global),
        Key("adaptive_protection_current_switch_value", Namespace.Global),
        Key("init_protection_to_adaptive", Namespace.Global),
        Key("prev_protect_battery", Namespace.Global),

        // Charging behaviour the user controls, and which genuinely affects wear: heat
        // and rate are the two biggest drivers of capacity loss after time. All three
        // were observed present on an SM-S948B. Recorded as context for a health figure,
        // never rendered as a health claim in themselves.
        Key("super_fast_charging", Namespace.System, "Samsung 45W wired fast charge toggle."),
        Key("wireless_fast_charging", Namespace.System, "Samsung fast wireless charge toggle."),
        Key("charging_info_always", Namespace.System),

        // Battery-saver state, which suppresses the foreground service this app's
        // measurement depends on -- observed doing exactly that during testing, where it
        // stopped ChargeRecorderService from starting at all.
        Key("low_power", Namespace.Global, "Battery saver. Blocks the sampling service."),
    )

    /**
     * Turns raw reads into probe results.
     *
     * [read] is given a key and returns its value, null when the provider has no such
     * setting, or throws when the platform refuses. Injected rather than taking a
     * `ContentResolver` so the classification is provable on the JVM.
     *
     * A missing key and a refused read are different outcomes for the same reason they are
     * everywhere else in this sweep: one says this device does not have the setting, the
     * other says it has it and will not show us.
     */
    fun resultsFrom(read: (Key) -> String?): List<ProbeResult> = keys.map { key ->
        val outcome = try {
            read(key)?.takeIf { it.isNotBlank() }
                ?.let { ProbeOutcome.Value(it) }
                ?: ProbeOutcome.Absent
        } catch (e: SecurityException) {
            ProbeOutcome.Denied
        } catch (t: Throwable) {
            val name = t::class.simpleName ?: "Throwable"
            val message = t.message?.takeIf { it.isNotBlank() }
            ProbeOutcome.Failed(if (message == null) name else "$name: $message")
        }
        ProbeResult(
            channel = ProbeChannel.Settings,
            key = "${key.namespace.name.lowercase()}/${key.key}",
            outcome = outcome,
        )
    }
}
