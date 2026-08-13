package com.mralaminahamed.batteryhealth.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mralaminahamed.batteryhealth.data.repo.BatteryRepository
import com.mralaminahamed.batteryhealth.domain.Reading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    repository: BatteryRepository,
) : ViewModel() {

    val state: StateFlow<HealthUiState> =
        combine(repository.snapshots(), repository.measuredHealth()) { snapshot, measured ->
            HealthUiState(snapshot, measured)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HealthUiState(snapshot = null, measured = Reading.NotYetMeasured),
        )
}
