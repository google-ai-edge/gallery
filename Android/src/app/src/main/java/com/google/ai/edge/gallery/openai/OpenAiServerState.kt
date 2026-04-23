package com.google.ai.edge.gallery.openai

import android.content.Context
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OpenAiServerState {
    private const val PREFS_NAME = "openai_server_prefs"
    private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"

    var modelManagerViewModel: ModelManagerViewModel? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _localUrl = MutableStateFlow<String?>(null)
    val localUrl = _localUrl.asStateFlow()

    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl = _publicUrl.asStateFlow()

    private val _isTunnelEnabled = MutableStateFlow(false)
    val isTunnelEnabled = _isTunnelEnabled.asStateFlow()

    fun setRunning(running: Boolean, local: String? = null, public: String? = null) {
        _isRunning.value = running
        _localUrl.value = local
        _publicUrl.value = public
    }

    fun setPublicUrl(url: String?) {
        _publicUrl.value = url
    }

    fun setTunnelEnabled(enabled: Boolean) {
        _isTunnelEnabled.value = enabled
        if (!enabled) {
            _publicUrl.value = null
        }
    }

    fun loadTunnelPreference(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isTunnelEnabled.value = prefs.getBoolean(KEY_TUNNEL_ENABLED, true)
    }

    fun persistTunnelEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TUNNEL_ENABLED, enabled)
            .apply()
        setTunnelEnabled(enabled)
    }
}
