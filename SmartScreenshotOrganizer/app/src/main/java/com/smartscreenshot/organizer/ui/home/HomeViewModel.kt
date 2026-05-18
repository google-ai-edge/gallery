package com.smartscreenshot.organizer.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartscreenshot.organizer.data.db.CategoryCount
import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import com.smartscreenshot.organizer.data.model.Category
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import com.smartscreenshot.organizer.search.SearchEngine
import com.smartscreenshot.organizer.worker.ScreenshotAnalysisWorker
import com.smartscreenshot.organizer.worker.ScreenshotMonitorWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val screenshots: List<ScreenshotEntity> = emptyList(),
    val categoryCounts: List<CategoryCount> = emptyList(),
    val selectedCategory: Category? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val totalCount: Int = 0,
    val unanalyzedCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: ScreenshotRepository,
    private val searchEngine: SearchEngine
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow<Category?>(null)

    val categoryCounts: StateFlow<List<CategoryCount>> = repository.observeCategoryCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<HomeUiState> = combine(
        _searchQuery,
        _selectedCategory,
        _searchQuery.flatMapLatest { query ->
            if (query.isNotBlank()) {
                searchEngine.search(query)
            } else {
                _selectedCategory.flatMapLatest { category ->
                    if (category != null) {
                        searchEngine.filterByCategory(category.name)
                    } else {
                        repository.observeRecent(50)
                    }
                }
            }
        },
        repository.observeCategoryCounts()
    ) { query, category, screenshots, counts ->
        HomeUiState(
            screenshots = screenshots,
            categoryCounts = counts,
            selectedCategory = category,
            searchQuery = query,
            isLoading = false,
            totalCount = screenshots.size
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeUiState()
    )

    init {
        // Ensure background monitoring is running
        val context: Context = getApplication()
        ScreenshotMonitorWorker.enqueuePeriodicWork(context)

        // Trigger initial scan
        viewModelScope.launch {
            ScreenshotAnalysisWorker.enqueueWork(context)
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelect(category: Category?) {
        _selectedCategory.value = if (_selectedCategory.value == category) null else category
    }

    fun triggerRescan() {
        val context: Context = getApplication()
        ScreenshotMonitorWorker.enqueuePeriodicWork(context)
        ScreenshotAnalysisWorker.enqueueWork(context)
    }
}
