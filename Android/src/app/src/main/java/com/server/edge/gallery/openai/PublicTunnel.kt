package com.server.edge.gallery.openai

import kotlinx.coroutines.flow.StateFlow

interface PublicTunnel {
    val publicUrl: StateFlow<String?>

    suspend fun start(localPort: Int)

    fun stop()
}
