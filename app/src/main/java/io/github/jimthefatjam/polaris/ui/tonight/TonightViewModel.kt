package io.github.jimthefatjam.polaris.ui.tonight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jimthefatjam.polaris.core.scoring.ObservationType
import io.github.jimthefatjam.polaris.data.ObservingRepository
import io.github.jimthefatjam.polaris.data.ObservingState
import io.github.jimthefatjam.polaris.data.SettingsRepository
import io.github.jimthefatjam.polaris.data.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.jimthefatjam.polaris.data.LocationMode

data class TonightUiState(
    val observing: ObservingState = ObservingState(),
    val settings: UserSettings = UserSettings(),
    val selected: ObservationType = ObservationType.GENERAL,
)

class TonightViewModel(
    private val observing: ObservingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val selected = MutableStateFlow(ObservationType.GENERAL)

    val state: StateFlow<TonightUiState> = combine(observing.state, settings.settings, selected) { o, s, sel ->
        TonightUiState(o, s, sel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightUiState())

    fun select(type: ObservationType) { selected.value = type }
    fun refresh(force: Boolean) = observing.refresh(force)
    fun tick() = observing.recomputeNow()

    fun onPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) settings.setLocationMode(LocationMode.DEVICE)
            observing.refresh(force = false)
        }
    }
}
