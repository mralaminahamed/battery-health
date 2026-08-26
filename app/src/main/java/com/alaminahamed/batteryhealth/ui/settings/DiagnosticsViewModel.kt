package com.alaminahamed.batteryhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.discovery.BatteryDiscovery
import com.alaminahamed.batteryhealth.data.vendor.discovery.BatteryDiscoveryReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs the discovery sweep on demand and holds its result.
 *
 * On demand rather than at startup, and deliberately not cached across runs. The sweep
 * exists to answer "what does *this* device offer *right now*" -- a platform flag can flip
 * across an OS update, a permission can be granted between launches, and a stale answer
 * about state of health is the one thing this screen must not show.
 *
 * It is also the only way a user can see what their own hardware exposes, which is the
 * point: this app's knowledge of any device other than the one it was developed on comes
 * from someone running this and reporting back.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val discovery: BatteryDiscovery,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    /**
     * The sweep does binder calls, a sticky-broadcast read, an XML parse and several
     * provider reads, so it runs off the main thread. None of it is slow enough to matter
     * individually; together they are more than belongs in a click handler.
     */
    fun run() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { discovery.sweep() }
            _state.value = DiagnosticsUiState(
                running = false,
                report = report,
                identity = discovery.describes,
            )
        }
    }
}

/**
 * @property report null until the user has actually run a sweep. Absence here means "not
 *   asked yet", which is distinct from a sweep that ran and found nothing -- the same
 *   distinction the sweep itself draws between every kind of absence.
 */
data class DiagnosticsUiState(
    val running: Boolean = false,
    val report: BatteryDiscoveryReport? = null,
    val identity: DeviceIdentity? = null,
)
