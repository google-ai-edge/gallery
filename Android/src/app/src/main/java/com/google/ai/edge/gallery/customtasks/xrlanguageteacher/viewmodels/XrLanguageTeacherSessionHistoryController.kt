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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.LessonHistorySession
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MESSAGE_TYPE_AR_VOCAB
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MESSAGE_TYPE_IMAGE
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MESSAGE_TYPE_TEXT
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveLessonSessionTitle
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toLessonHistorySession
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.proto.chatMessageProto
import com.google.ai.edge.gallery.proto.chatSessionProto
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persists the active lesson session to [XrLanguageTeacherViewModel.chatSessionRepository] after a
 * completed teacher turn (including single-turn object scans without follow-up questions).
 * Greeting-only sessions with no completed teacher turn are not saved.
 */
internal fun XrLanguageTeacherViewModel.saveCurrentLessonSession() {
  val state = _uiState.value
  val primaryVocabCard = state.vocabCards.firstOrNull() ?: state.vocabCardData
  val hasCompletedTeacherTurn = state.chatTurns.any { !it.isUser } || primaryVocabCard != null
  if (!hasCompletedTeacherTurn) return

  if (currentSessionStartTimestampMs == 0L) {
    currentSessionStartTimestampMs = System.currentTimeMillis()
  }
  val sessionIdSnapshot = currentSessionIdInternal
  if (sessionIdSnapshot in deletedSessionIds) return

  val timestampSnapshot = currentSessionStartTimestampMs
  val cachedImagePathSnapshot = currentSessionImagePath
  val bitmapSnapshot = state.capturedBitmap
  val greetingSnapshot = state.sessionGreeting
  val personaSnapshot = selectedPersona.value
  val mainLanguageSnapshot = mainLanguage.value
  val learnLanguageSnapshot = learnLanguage.value
  val chatTurnsSnapshot = state.chatTurns.toList()
  val modelNameSnapshot = activeSessionModelName.orEmpty()
  val untitledFallback = storageRepository.getString(R.string.xrteacher_session_untitled)
  val title =
    resolveLessonSessionTitle(
      turns = chatTurnsSnapshot,
      vocabWord = primaryVocabCard?.word,
      untitledFallback = untitledFallback,
    )

  viewModelScope.launch(ioDispatcher) {
    sessionSaveMutex.withLock {
      if (sessionIdSnapshot in deletedSessionIds) return@withLock
      var resolvedImagePath = cachedImagePathSnapshot
      if (resolvedImagePath == null && bitmapSnapshot != null && !bitmapSnapshot.isRecycled) {
        resolvedImagePath =
          storageRepository.saveSessionImage(
            sessionId = sessionIdSnapshot,
            timestampMs = timestampSnapshot,
            bitmap = bitmapSnapshot,
          )
        if (resolvedImagePath != null && currentSessionIdInternal == sessionIdSnapshot) {
          currentSessionImagePath = resolvedImagePath
        }
      }

      // `content` keeps the legacy "word\nmeaning" form for older app versions.
      fun vocabCardMessage(card: VocabCardData) = chatMessageProto {
        messageType = MESSAGE_TYPE_AR_VOCAB
        side = ChatSideProto.CHAT_SIDE_MODEL
        content = "${card.word}\n${card.meaning}"
        personaId = personaSnapshot.id
        vocabCard = card.toVocabCardProto(mainLanguageSnapshot, learnLanguageSnapshot)
      }

      val messages = buildList {
        if (greetingSnapshot.isNotBlank()) {
          add(
            chatMessageProto {
              messageType = MESSAGE_TYPE_TEXT
              side = ChatSideProto.CHAT_SIDE_MODEL
              content = greetingSnapshot
              personaId = personaSnapshot.id
              hideSenderLabel = true
            }
          )
        }
        if (resolvedImagePath != null) {
          add(
            chatMessageProto {
              messageType = MESSAGE_TYPE_IMAGE
              side = ChatSideProto.CHAT_SIDE_USER
              imageFilePaths.add(resolvedImagePath)
            }
          )
        }
        if (primaryVocabCard != null && primaryVocabCard.word.isNotBlank()) {
          add(vocabCardMessage(primaryVocabCard))
        }
        for (turn in chatTurnsSnapshot) {
          add(
            chatMessageProto {
              messageType = MESSAGE_TYPE_TEXT
              side =
                if (turn.isUser) ChatSideProto.CHAT_SIDE_USER else ChatSideProto.CHAT_SIDE_MODEL
              content = turn.text
              if (!turn.isUser) {
                personaId = personaSnapshot.id
              }
            }
          )
          val turnCard = turn.vocabCard
          if (!turn.isUser && turnCard != null && turnCard.word.isNotBlank()) {
            add(vocabCardMessage(turnCard))
          }
        }
      }

      val sessionProto = chatSessionProto {
        sessionId = sessionIdSnapshot
        this.title = title
        timestampMs = timestampSnapshot
        originalModel = modelNameSnapshot
        taskId = BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER
        this.messages.addAll(messages)
      }

      chatSessionRepository.saveChatSession(sessionProto)
    }
  }
}

/**
 * Builds the ordered list of [LessonHistorySession] items for the Session history side sheet.
 *
 * If the currently active lesson session has at least one completed teacher turn (or detected
 * vocabulary card), it appears at the top with `isActiveSession = true` and live-updating turns and
 * image, followed by persisted past sessions ordered newest-first.
 */
fun XrLanguageTeacherViewModel.buildDisplayLessonSessions(
  uiState: XrLanguageTeacherUiState = _uiState.value,
  persistedSessions: List<ChatSessionProto> = historySessions.value,
): List<LessonHistorySession> {
  val untitledFallback = storageRepository.getString(R.string.xrteacher_session_untitled)
  val activeId = currentSessionIdInternal
  val primaryCard = uiState.vocabCards.firstOrNull() ?: uiState.vocabCardData
  val hasActiveCompletedTeacherTurn =
    uiState.isSessionActive && (uiState.chatTurns.any { !it.isUser } || primaryCard != null)
  val persistedActive =
    persistedSessions
      .firstOrNull { it.sessionId == activeId }
      ?.toLessonHistorySession(untitledFallback)

  val activeEntry =
    if (hasActiveCompletedTeacherTurn) {
      val resolvedTitle =
        resolveLessonSessionTitle(
          turns = uiState.chatTurns,
          vocabWord = primaryCard?.word ?: persistedActive?.vocabWord,
          untitledFallback = persistedActive?.title?.takeIf { it.isNotEmpty() } ?: untitledFallback,
        )
      val resolvedTimestamp =
        when {
          currentSessionStartTimestampMs > 0L -> currentSessionStartTimestampMs
          persistedActive != null && persistedActive.timestampMs > 0L -> persistedActive.timestampMs
          else -> System.currentTimeMillis()
        }
      LessonHistorySession(
        sessionId = activeId,
        title = resolvedTitle,
        timestampMs = resolvedTimestamp,
        persona = selectedPersona.value,
        greeting = uiState.sessionGreeting.ifBlank { persistedActive?.greeting.orEmpty() },
        imageFilePath = currentSessionImagePath ?: persistedActive?.imageFilePath,
        inMemoryBitmap = uiState.capturedBitmap,
        vocabWord =
          primaryCard?.word?.trim()?.takeIf { it.isNotEmpty() } ?: persistedActive?.vocabWord,
        vocabMeaning =
          primaryCard?.meaning?.trim()?.takeIf { it.isNotEmpty() } ?: persistedActive?.vocabMeaning,
        primaryVocabCard =
          primaryCard?.takeIf { it.word.isNotBlank() } ?: persistedActive?.primaryVocabCard,
        mainLanguage = mainLanguage.value,
        learnLanguage = learnLanguage.value,
        turns = uiState.chatTurns,
        isActiveSession = true,
      )
    } else {
      null
    }

  val pastEntries =
    persistedSessions
      .filter { !hasActiveCompletedTeacherTurn || it.sessionId != activeId }
      .sortedByDescending { it.timestampMs }
      .map { it.toLessonHistorySession(untitledFallback) }

  return if (activeEntry != null) {
    listOf(activeEntry) + pastEntries
  } else {
    pastEntries
  }
}

/** Loads a persisted session's captured object image from [imageFilePath] off the main thread. */
suspend fun XrLanguageTeacherViewModel.loadSessionImageBitmap(imageFilePath: String): ImageBitmap? =
  withContext(ioDispatcher) {
    try {
      val file = File(imageFilePath)
      if (!file.exists() || !file.isFile || file.length() == 0L) {
        return@withContext null
      }
      BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode session image at '$imageFilePath'", e)
      null
    }
  }

/**
 * Deletes a lesson session by [sessionId] from [XrLanguageTeacherViewModel.chatSessionRepository]
 * and cleans up its session image files via [XrLanguageTeacherViewModel.storageRepository]. If
 * [sessionId] is the currently active session, resets the active lesson session back to the live
 * camera viewfinder.
 */
fun XrLanguageTeacherViewModel.deleteLessonSession(
  sessionId: String,
  model: Model? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  deletedSessionIds.add(sessionId)
  if (sessionId == currentSessionIdInternal) {
    resetLessonSession(model = model, onRunningStateChanged = onRunningStateChanged)
  }
  logChatHistoryAction(action = "delete_session", model = model)
  viewModelScope.launch(ioDispatcher) {
    sessionSaveMutex.withLock {
      chatSessionRepository.deleteChatSession(sessionId)
      storageRepository.deleteSessionImages(sessionId)
    }
  }
}

/**
 * Deletes every persisted AR Language Teacher session, plus the active one, and their cached
 * images. Sessions from other tasks are left untouched.
 */
fun XrLanguageTeacherViewModel.deleteAllLessonSessions(
  model: Model? = null,
  onRunningStateChanged: (Boolean) -> Unit = {},
) {
  for (session in historySessions.value) {
    deletedSessionIds.add(session.sessionId)
  }
  // Like deleteLessonSession, only reset when the current lesson is among the deleted ones; an
  // idle session has nothing persisted, and resetting it would replay the greeting.
  if (_uiState.value.isSessionActive) {
    deletedSessionIds.add(currentSessionIdInternal)
    resetLessonSession(model = model, onRunningStateChanged = onRunningStateChanged)
  }
  // Sessions saved under the (possibly new) current id after this point must survive.
  val keptSessionId = currentSessionIdInternal
  logChatHistoryAction(action = "clear_all_sessions", model = model)
  viewModelScope.launch(ioDispatcher) {
    sessionSaveMutex.withLock {
      val teacherSessionIds =
        chatSessionRepository
          .getAllChatSessions()
          .filter {
            it.taskId == BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER && it.sessionId != keptSessionId
          }
          .map { it.sessionId }
      for (sessionId in teacherSessionIds) {
        chatSessionRepository.deleteChatSession(sessionId)
      }
      storageRepository.deleteSessionImagesForIds(teacherSessionIds.toSet())
    }
  }
}

/** Logs a `chat_history` analytics event for XR Language Teacher session history interactions. */
fun XrLanguageTeacherViewModel.logChatHistoryAction(action: String, model: Model? = null) {
  val modelId = model?.name ?: activeSessionModelName.orEmpty()
  val modelVersion = model?.downloadInfo?.version.orEmpty()
  Log.d(
    TAG,
    "Analytics: chat_history, action=$action, capability_name=${BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER}, model_id=$modelId, model_version=$modelVersion",
  )
  firebaseAnalytics?.logEvent(
    GalleryEvent.CHAT_HISTORY.id,
    Bundle().apply {
      putString("action", action)
      putString("capability_name", BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER)
      if (modelId.isNotEmpty()) {
        putString("model_id", modelId)
      }
      if (modelVersion.isNotEmpty()) {
        putString("model_version", modelVersion)
      }
    },
  )
}
