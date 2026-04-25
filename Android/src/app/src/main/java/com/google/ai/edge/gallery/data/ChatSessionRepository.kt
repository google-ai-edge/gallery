package com.google.ai.edge.gallery.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence layer for chat sessions using SharedPreferences + JSON.
 * Provides save/load/delete operations matching the Flutter AppStorage chat API.
 */
class ChatSessionRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "chat_sessions_prefs"
        private const val KEY_SESSIONS = "chat_sessions"
        private const val KEY_ACTIVE_CHAT_ID = "active_chat_id"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSessions(): List<ChatSession> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return ChatSession.fromJson(json)
    }

    fun saveSessions(sessions: List<ChatSession>) {
        prefs.edit()
            .putString(KEY_SESSIONS, ChatSession.toJson(sessions))
            .apply()
    }

    fun upsertSession(session: ChatSession) {
        val sessions = loadSessions().toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = session
        } else {
            sessions.add(0, session)
        }
        saveSessions(sessions)
    }

    fun deleteSession(sessionId: String) {
        val sessions = loadSessions().filter { it.id != sessionId }
        saveSessions(sessions)
        if (getActiveChatId() == sessionId) {
            setActiveChatId(null)
        }
    }

    fun getActiveChatId(): String? {
        return prefs.getString(KEY_ACTIVE_CHAT_ID, null)
    }

    fun setActiveChatId(id: String?) {
        if (id.isNullOrEmpty()) {
            prefs.edit().remove(KEY_ACTIVE_CHAT_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_CHAT_ID, id).apply()
        }
    }
}
