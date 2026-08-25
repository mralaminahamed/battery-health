package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DesignCapacityProvider.effective` itself needs a real `SettingsStore`, which needs a
 * real `Context` -- not available to a plain JVM test in this project (no Robolectric,
 * no third-party test doubles; see the module's hard constraint on new dependencies).
 * The precedence rule it applies (override beats table beats nothing) is pure, though,
 * so it is pulled out as [DesignCapacityProvider.resolve] purely so this -- override
 * wins, clearing falls back -- is provable on the JVM instead of only through an
 * instrumented test this environment cannot even run.
 */
class DesignCapacityProviderTest {

    @Test
    fun anOverrideWinsOverAKnownTableEntry() {
        val result = DesignCapacityProvider.resolve(overrideMah = 5555, model = "SM-A356E")
        assertEquals(EffectiveDesignCapacity(5555, DesignCapacitySource.Override), result)
    }

    @Test
    fun clearingTheOverrideFallsBackToTheTable() {
        val result = DesignCapacityProvider.resolve(overrideMah = null, model = "SM-A356E")
        assertEquals(EffectiveDesignCapacity(5000, DesignCapacitySource.Table), result)
    }

    @Test
    fun anOverrideWinsEvenOnAnUnknownModel() {
        // The whole point of the override: it must work precisely where the table can't.
        val result = DesignCapacityProvider.resolve(overrideMah = 4500, model = "SM-S948B")
        assertEquals(EffectiveDesignCapacity(4500, DesignCapacitySource.Override), result)
    }

    @Test
    fun neitherAnOverrideNorATableEntryReportsNone() {
        // A model the table genuinely does not carry. Deliberately not a real device that
        // might later be added to the table, which would silently turn this test green for
        // the wrong reason.
        val result = DesignCapacityProvider.resolve(overrideMah = null, model = "SM-X999Z")
        assertEquals(EffectiveDesignCapacity.None, result)
    }
}
