package com.alaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Play build has no privileged tier at all.
 *
 * Everything the shell used to supply now comes from somewhere that costs the user
 * nothing: state of health and both dates from a granted `BATTERY_STATS`, Battery Protect
 * and its charge limit from `Settings.Global`, and the cycle count from this app's own
 * recorded charge sessions. What is left -- Samsung's *lifetime* cycle count and its BSOH
 * figure -- duplicates values the app already produces, differing only in counting from
 * the battery's manufacture rather than from install.
 *
 * That is not worth an app that opens sockets. Removing the transport from this flavour
 * takes the `INTERNET` permission out of the shipped manifest entirely, ends the "why does
 * a battery app need internet?" question at review, and makes it structurally impossible
 * for this build to raise Android's "Allow USB debugging?" dialog at someone who never
 * asked for it -- which it did, on real hardware, before any of this.
 *
 * The real transport is not deleted. It still builds and is still tested in the `full`
 * flavour, which is distributed outside Play and where per-app attribution needs it.
 *
 * [connect] and [refresh] are deliberately no-ops rather than throwing. Callers reach them
 * from a button this flavour's UI never shows, and a crash would be a worse answer than
 * doing nothing to a build that has nothing to do.
 */
@Singleton
class NoPrivilegedTier @Inject constructor() : PrivilegedBatterySource {

    private val _state = MutableStateFlow<PrivilegedAvailability>(PrivilegedAvailability.Unavailable)
    override val state: StateFlow<PrivilegedAvailability> = _state.asStateFlow()

    override suspend fun dumpBattery(): String? = null

    override suspend fun dumpBatteryStatsCheckin(): String? = null

    override suspend fun connect() = Unit

    override fun refresh() = Unit
}
