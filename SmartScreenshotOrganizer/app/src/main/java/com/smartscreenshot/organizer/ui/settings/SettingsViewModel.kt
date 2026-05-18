package com.smartscreenshot.organizer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscreenshot.organizer.ai.InferenceProviderFactory
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import com.smartscreenshot.organizer.settings.AppPreferences
import com.smartscreenshot.organizer.worker.ScreenshotAnalysisWorker
import com.smartscreenshot.organizer.worker.ScreenshotMonitorWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val inferenceMode: String = AppPreferences.INFERENCE_AUTO,
    val httpEndpoint: String = AppPreferences.DEFAULT_HTTP_ENDPOINT,
    val autoAnalyze: Boolean = true,
    val darkMode: String = AppPreferences.DARK_MODE_SYSTEM,
    val totalScreenshots: Int = 0,
    val unanalyzedScreenshots: Int = 0,
    val aiCoreAvailable: Boolean = false,
    val httpAvailable: Boolean = false,
    val isReindexing: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val preferences: AppPreferences,
    private val repository: ScreenshotRepository,
    private val inferenceFactory: InferenceProviderFactory
) : AndroidViewModel(application) {

    private val _isReindexing = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.inferenceMode,
        preferences.httpEndpoint,
        preferences.autoAnalyze,
        preferences.darkMode,
        _isReindexing
    ) { mode, endpoint, autoAnalyze, darkMode, reindexing ->
        val total = repository.getTotalCount()
        val unanalyzed = repository.getUnanalyzedCount()
        val providers = inferenceFactory.getAvailableProviders()

        SettingsUiState(
            inferenceMode = mode,
            httpEndpoint = endpoint,
            autoAnalyze = autoAnalyze,
            darkMode = darkMode,
            totalScreenshots = total,
            unanalyzedScreenshots = unanalyzed,
            aiCoreAvailable = providers.any { it.first.providerName().contains("AI Core") && it.second },
            httpAvailable = providers.any { it.first.providerName().contains("HTTP") && it.second },
            isReindexing = reindexing
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsUiState()
    )

    fun setInferenceMode(mode: String) {
        viewModelScope.launch { preferences.setInferenceMode(mode) }
    }

    fun setHttpEndpoint(endpoint: String) {
        viewModelScope.launch { preferences.setHttpEndpoint(endpoint) }
    }

    fun setAutoAnalyze(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoAnalyze(enabled) }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch { preferences.setDarkMode(mode) }
    }

    fun reindexAll() {
        viewModelScope.launch {
            _isReindexing.value = true
            repository.resetAllAnalysis()
            ScreenshotMonitorWorker.enqueuePeriodicWork(getApplication())
            ScreenshotAnalysisWorker.enqueueWork(getApplication())
            _isReindexing.value = false
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }
}
