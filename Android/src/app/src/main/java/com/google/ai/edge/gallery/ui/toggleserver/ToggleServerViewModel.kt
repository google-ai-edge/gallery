package com.google.ai.edge.gallery.ui.toggleserver

import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.server.InAppServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Log

@HiltViewModel
class ToggleServerViewModel @Inject constructor(
    private val inAppServer: InAppServer
) : ViewModel() {

    private val _isServerRunning = MutableStateFlow(inAppServer.isRunning())
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    fun toggleServer() {
        Log.d("ToggleServerViewModel", "toggleServer called")
        if (inAppServer.isRunning()) {
            inAppServer.stop()
        } else {
            inAppServer.start()
        }
        _isServerRunning.value = inAppServer.isRunning()
    }

    override fun onCleared() {
        super.onCleared()
        inAppServer.stop()
    }
}
