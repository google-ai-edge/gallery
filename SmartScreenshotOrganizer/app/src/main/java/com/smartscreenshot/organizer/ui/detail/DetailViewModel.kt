package com.smartscreenshot.organizer.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val screenshot: ScreenshotEntity? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ScreenshotRepository
) : ViewModel() {

    private val screenshotId: Long = savedStateHandle.get<Long>("screenshotId") ?: -1L

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        loadScreenshot()
    }

    private fun loadScreenshot() {
        viewModelScope.launch {
            _uiState.value = DetailUiState(isLoading = true)

            try {
                val screenshot = repository.getById(screenshotId)
                _uiState.value = DetailUiState(
                    screenshot = screenshot,
                    isLoading = false,
                    error = if (screenshot == null) "Screenshot not found" else null
                )
            } catch (e: Exception) {
                _uiState.value = DetailUiState(
                    isLoading = false,
                    error = "Failed to load screenshot: ${e.message}"
                )
            }
        }
    }

    fun deleteScreenshot() {
        viewModelScope.launch {
            repository.deleteById(screenshotId)
        }
    }
}
