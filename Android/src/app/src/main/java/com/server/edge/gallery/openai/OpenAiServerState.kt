package com.server.edge.gallery.openai

import android.content.Context
import com.server.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OpenAiServerState {
    const val TUNNEL_PROVIDER_CLOUDFLARE = "cloudflare"
    const val TUNNEL_PROVIDER_NGROK = "ngrok"

    private const val PREFS_NAME = "openai_server_prefs"
    private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"
    private const val KEY_TUNNEL_PROVIDER = "tunnel_provider"
    private const val KEY_CLOUDFLARE_TUNNEL_TOKEN = "cloudflare_tunnel_token"
    private const val KEY_CLOUDFLARE_PUBLIC_URL = "cloudflare_public_url"
    private const val KEY_NGROK_AUTH_TOKEN = "ngrok_auth_token"
    private const val KEY_NGROK_DOMAIN = "ngrok_domain"

    var modelManagerViewModel: ModelManagerViewModel? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _localUrl = MutableStateFlow<String?>(null)
    val localUrl = _localUrl.asStateFlow()

    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl = _publicUrl.asStateFlow()

    private val _isTunnelEnabled = MutableStateFlow(false)
    val isTunnelEnabled = _isTunnelEnabled.asStateFlow()

    private val _tunnelProvider = MutableStateFlow(TUNNEL_PROVIDER_NGROK)
    val tunnelProvider = _tunnelProvider.asStateFlow()

    private val _openServerScreenRequest = MutableStateFlow(0L)
    val openServerScreenRequest = _openServerScreenRequest.asStateFlow()

    fun requestOpenServerScreen() {
        _openServerScreenRequest.value = System.currentTimeMillis()
    }

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
        _tunnelProvider.value =
            prefs.getString(KEY_TUNNEL_PROVIDER, TUNNEL_PROVIDER_NGROK)
                ?.takeIf { it == TUNNEL_PROVIDER_CLOUDFLARE || it == TUNNEL_PROVIDER_NGROK }
                ?: TUNNEL_PROVIDER_NGROK
    }

    fun persistTunnelEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TUNNEL_ENABLED, enabled)
            .apply()
        setTunnelEnabled(enabled)
    }

    fun persistTunnelProvider(context: Context, provider: String) {
        val normalized =
            if (provider == TUNNEL_PROVIDER_NGROK) TUNNEL_PROVIDER_NGROK else TUNNEL_PROVIDER_CLOUDFLARE
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TUNNEL_PROVIDER, normalized)
            .apply()
        _tunnelProvider.value = normalized
    }

    fun persistCloudflareTunnelConfig(context: Context, tunnelToken: String, publicUrl: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUDFLARE_TUNNEL_TOKEN, tunnelToken.trim())
            .putString(KEY_CLOUDFLARE_PUBLIC_URL, publicUrl.trim().trimEnd('/'))
            .apply()
    }

    fun cloudflareTunnelToken(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLOUDFLARE_TUNNEL_TOKEN, "")
            .orEmpty()
    }

    fun cloudflarePublicUrl(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLOUDFLARE_PUBLIC_URL, "")
            .orEmpty()
    }

    fun persistNgrokConfig(context: Context, authToken: String, domain: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NGROK_AUTH_TOKEN, authToken.trim())
            .putString(KEY_NGROK_DOMAIN, domain.trim().trimEnd('/'))
            .apply()
    }

    fun ngrokAuthToken(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NGROK_AUTH_TOKEN, "27EU8xAlKpERmOQPpeGRn4jTsg6_6GCfUawhJEiandVRs7hjD")
            .orEmpty()
    }

    fun ngrokDomain(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NGROK_DOMAIN, "gratuitously-pentahydric-laila.ngrok-free.dev")
            .orEmpty()
    }
}
