package com.soundsight.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundsight.core.AppLanguage
import com.soundsight.core.OnboardingStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val selectedLanguage: AppLanguage = AppLanguage.VI,
    val currentPage: Int = 0,
    val disclaimerAccepted: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val isLoading: Boolean = false
)

sealed interface OnboardingEvent {
    data class SelectLanguage(val language: AppLanguage) : OnboardingEvent
    data object ConfirmLanguage : OnboardingEvent
    data class CarouselPageChanged(val page: Int) : OnboardingEvent
    data object SkipCarousel : OnboardingEvent
    data object NextCarouselPage : OnboardingEvent
    data object AcceptDisclaimer : OnboardingEvent
    data object RequestMicPermission : OnboardingEvent
    data class MicPermissionResult(val granted: Boolean) : OnboardingEvent

    /**
     * The "Not now" decline on the permission screen: onboarding counts as complete and the
     * rider enters the HUD degraded, with no microphone grant recorded. This is what keeps a
     * denied RECORD_AUDIO from trapping the rider before the HUD - see [PermissionScreen].
     */
    data object DeclineMicPermission : OnboardingEvent
}

sealed interface OnboardingEffect {
    data object NavigateToCarousel : OnboardingEffect
    data object NavigateToDisclaimer : OnboardingEffect
    data object NavigateToPermission : OnboardingEffect
    data object LaunchPermissionRequest : OnboardingEffect
    data object NavigateToHud : OnboardingEffect
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
    val effects: Flow<OnboardingEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val savedLang = OnboardingStore.languageFlow(getApplication()).first()
                val disclaimerAccepted = OnboardingStore.hasAcceptedDisclaimerFlow(getApplication()).first()
                _uiState.update {
                    it.copy(
                        selectedLanguage = savedLang,
                        disclaimerAccepted = disclaimerAccepted
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.SelectLanguage -> {
                _uiState.update { it.copy(selectedLanguage = event.language) }
                viewModelScope.launch {
                    try {
                        OnboardingStore.setLanguage(getApplication(), event.language)
                    } catch (_: Exception) {}
                }
            }

            is OnboardingEvent.ConfirmLanguage -> {
                viewModelScope.launch {
                    _effects.send(OnboardingEffect.NavigateToCarousel)
                }
            }

            is OnboardingEvent.CarouselPageChanged -> {
                _uiState.update { it.copy(currentPage = event.page) }
            }

            is OnboardingEvent.SkipCarousel -> {
                viewModelScope.launch {
                    _effects.send(OnboardingEffect.NavigateToDisclaimer)
                }
            }

            is OnboardingEvent.NextCarouselPage -> {
                val current = _uiState.value.currentPage
                if (current < 2) {
                    _uiState.update { it.copy(currentPage = current + 1) }
                } else {
                    viewModelScope.launch {
                        _effects.send(OnboardingEffect.NavigateToDisclaimer)
                    }
                }
            }

            is OnboardingEvent.AcceptDisclaimer -> {
                _uiState.update { it.copy(disclaimerAccepted = true) }
                viewModelScope.launch {
                    try {
                        OnboardingStore.setDisclaimerAccepted(getApplication(), true)
                    } catch (_: Exception) {}
                    _effects.send(OnboardingEffect.NavigateToPermission)
                }
            }

            is OnboardingEvent.RequestMicPermission -> {
                viewModelScope.launch {
                    _effects.send(OnboardingEffect.LaunchPermissionRequest)
                }
            }

            is OnboardingEvent.MicPermissionResult -> {
                _uiState.update { it.copy(micPermissionGranted = event.granted) }
                if (event.granted) {
                    viewModelScope.launch {
                        try {
                            OnboardingStore.setOnboardingCompleted(getApplication(), true)
                        } catch (_: Exception) {}
                        _effects.send(OnboardingEffect.NavigateToHud)
                    }
                }
            }

            is OnboardingEvent.DeclineMicPermission -> {
                _uiState.update { it.copy(micPermissionGranted = false) }
                viewModelScope.launch {
                    try {
                        OnboardingStore.setOnboardingCompleted(getApplication(), true)
                    } catch (_: Exception) {}
                    _effects.send(OnboardingEffect.NavigateToHud)
                }
            }
        }
    }
}
