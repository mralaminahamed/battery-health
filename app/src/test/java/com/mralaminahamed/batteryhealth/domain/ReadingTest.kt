package com.mralaminahamed.batteryhealth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {

    @Test
    fun availableExposesValueAndSource() {
        val reading = Reading.Available(86, Source.Privileged)
        assertTrue(reading.isAvailable)
        assertEquals(86, reading.valueOrNull())
        assertEquals(Source.Privileged, reading.source)
    }

    @Test
    fun absentStatesExposeNoValue() {
        val absent = listOf(Reading.Unsupported, Reading.NeedsPrivilegedAccess, Reading.NotYetMeasured)
        absent.forEach { reading ->
            assertFalse(reading.isAvailable)
            assertNull(reading.valueOrNull())
        }
    }

    @Test
    fun mapTransformsValueAndPreservesSource() {
        val mapped = Reading.Available(5000, Source.Framework).map { it / 1000 }
        assertEquals(Reading.Available(5, Source.Framework), mapped)
    }

    @Test
    fun mapOnAbsentReadingPreservesTheReason() {
        assertEquals(Reading.NeedsPrivilegedAccess, Reading.NeedsPrivilegedAccess.map { 1 })
        assertEquals(Reading.Unsupported, Reading.Unsupported.map { 1 })
        assertEquals(Reading.NotYetMeasured, Reading.NotYetMeasured.map { 1 })
    }

    @Test
    fun healthBandClassifiesByPercentage() {
        assertEquals(HealthBand.Good, HealthBand.of(86))
        assertEquals(HealthBand.Good, HealthBand.of(80))
        assertEquals(HealthBand.Fair, HealthBand.of(79))
        assertEquals(HealthBand.Fair, HealthBand.of(65))
        assertEquals(HealthBand.Poor, HealthBand.of(64))
    }
}
