package com.alaminahamed.batteryhealth.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.repo.BatteryRepository
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    repository: BatteryRepository,
) : ViewModel() {

    /**
     * Null means "no reading has arrived yet", which the UI renders as a waiting state.
     * It is never collapsed into a snapshot of zeroes.
     */
    val snapshot: StateFlow<BatterySnapshot?> = repository.snapshots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
