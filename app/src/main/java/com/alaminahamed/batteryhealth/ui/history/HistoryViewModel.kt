package com.alaminahamed.batteryhealth.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.repo.HistoryRepository
import com.alaminahamed.batteryhealth.domain.ChargeSession
import com.alaminahamed.batteryhealth.domain.HistoryRange
import com.alaminahamed.batteryhealth.domain.LevelPoint
import com.alaminahamed.batteryhealth.sampling.NowMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryUiState(
    val range: HistoryRange,
    val points: List<LevelPoint>,
    val sessions: List<ChargeSession>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    repository: HistoryRepository,
    nowMs: NowMs,
) : ViewModel() {

    private val range = MutableStateFlow(HistoryRange.Day)

    val state: StateFlow<HistoryUiState> = range
        .flatMapLatest { selected ->
            combine(
                repository.levelSeries(selected, nowMs.get()),
                repository.sessions(limit = SESSION_LIMIT),
            ) { points, sessions -> HistoryUiState(selected, points, sessions) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(HistoryRange.Day, emptyList(), emptyList()),
        )

    fun selectRange(selected: HistoryRange) {
        range.value = selected
    }

    private companion object {
        const val SESSION_LIMIT = 30
    }
}
