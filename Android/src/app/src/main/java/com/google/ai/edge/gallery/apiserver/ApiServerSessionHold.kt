package com.google.ai.edge.gallery.apiserver

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which model name, if any, the local API server currently needs kept alive.
 *
 * [DefaultAgentRuntimeExecutor.cleanUp] checks this before tearing down the active session: if
 * the session's model matches [heldModelName], cleanup is skipped so a screen closing (AI Chat,
 * Ask Image, etc.) doesn't unload a model the API server still needs. Plain in-memory state, not a
 * DataStore round-trip: `cleanUp()` is not suspend-safe and must answer synchronously.
 */
@Singleton
class ApiServerSessionHold @Inject constructor() {
  @Volatile var heldModelName: String? = null
}
