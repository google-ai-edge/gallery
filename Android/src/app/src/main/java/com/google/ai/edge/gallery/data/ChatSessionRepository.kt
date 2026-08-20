/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.UserData
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Repository for persisting and managing chat sessions and message histories. */
interface ChatSessionRepository {
  /** Reactive flow emitting all chat sessions sorted by most recent timestamp descending. */
  val chatSessions: Flow<List<ChatSessionProto>>

  /** Fetches all saved chat sessions once. */
  suspend fun getAllChatSessions(): List<ChatSessionProto>

  /** Saves or updates a chat session (upsert by [ChatSessionProto.sessionId]). */
  suspend fun saveChatSession(session: ChatSessionProto)

  /** Deletes a chat session by its unique session ID. */
  suspend fun deleteChatSession(sessionId: String)

  /** Clears all saved chat sessions. */
  suspend fun clearAllChatSessions()
}

/** Default implementation of [ChatSessionRepository] backed by [UserData] Proto DataStore. */
@Singleton
open class DefaultChatSessionRepository
@Inject
constructor(private val userDataDataStore: DataStore<UserData>) : ChatSessionRepository {

  override val chatSessions: Flow<List<ChatSessionProto>> =
    userDataDataStore.data.map { userData ->
      userData.chatSessionsList.sortedByDescending { it.timestampMs }
    }

  override suspend fun getAllChatSessions(): List<ChatSessionProto> {
    return userDataDataStore.data.first().chatSessionsList
  }

  override suspend fun saveChatSession(session: ChatSessionProto) {
    userDataDataStore.updateData { userData ->
      val currentSessions = userData.chatSessionsList.toMutableList()
      currentSessions.removeAll { it.sessionId == session.sessionId }
      currentSessions.add(session)
      userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
    }
  }

  override suspend fun deleteChatSession(sessionId: String) {
    userDataDataStore.updateData { userData ->
      val currentSessions = userData.chatSessionsList.filter { it.sessionId != sessionId }
      userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
    }
  }

  override suspend fun clearAllChatSessions() {
    userDataDataStore.updateData { userData -> userData.toBuilder().clearChatSessions().build() }
  }
}
