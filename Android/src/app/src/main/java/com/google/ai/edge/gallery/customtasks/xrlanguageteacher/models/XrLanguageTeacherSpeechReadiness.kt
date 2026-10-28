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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrServiceError

/**
 * Whether [language] is supported by a speech engine that advertises [supportedLocaleTags]. An
 * empty tag list means the engine does not restrict its languages.
 */
internal fun isLanguageSupportedByEngineLocales(
  language: Language,
  supportedLocaleTags: Collection<String>,
): Boolean = resolveEngineLocaleTagForLanguage(language, supportedLocaleTags) != null

/** A line of the "Initializing Teacher Model…" overlay. */
enum class TeacherInitStepKind(@StringRes val labelResId: Int) {
  /** The main model that writes the teacher's answers. */
  TEACHER_MODEL(R.string.xrteacher_init_step_teacher_model),

  /** The selected speech recognition engine. */
  SPEECH_RECOGNITION(R.string.xrteacher_init_step_speech_recognition),

  /** The selected text-to-speech engine. */
  VOICE(R.string.xrteacher_init_step_voice),
}

/** How far along a [TeacherInitStepKind] is. */
enum class TeacherInitStepStatus {
  IN_PROGRESS,
  READY,
  FAILED,
}

/** A line of the "Initializing Teacher Model…" overlay and its status. */
data class TeacherInitStep(val kind: TeacherInitStepKind, val status: TeacherInitStepStatus)

/** Maps a speech engine's [SpeechEngineReadiness] to its overlay line status. */
fun SpeechEngineReadiness.toInitStepStatus(): TeacherInitStepStatus =
  when (this) {
    SpeechEngineReadiness.READY -> TeacherInitStepStatus.READY
    SpeechEngineReadiness.WARMING -> TeacherInitStepStatus.IN_PROGRESS
    SpeechEngineReadiness.ERROR -> TeacherInitStepStatus.FAILED
  }

/**
 * The three lines of the "Initializing Teacher Model…" overlay, in display order: the teacher
 * model, speech recognition and voice.
 */
internal fun teacherInitSteps(
  teacherModelStatus: TeacherInitStepStatus,
  asrReadiness: SpeechEngineReadiness,
  ttsReadiness: SpeechEngineReadiness,
): List<TeacherInitStep> =
  listOf(
    TeacherInitStep(TeacherInitStepKind.TEACHER_MODEL, teacherModelStatus),
    TeacherInitStep(TeacherInitStepKind.SPEECH_RECOGNITION, asrReadiness.toInitStepStatus()),
    TeacherInitStep(TeacherInitStepKind.VOICE, ttsReadiness.toInitStepStatus()),
  )

/**
 * Why a recording that failed with [error] means the ASR engine is unusable, or `null` if the
 * failure only affects this attempt (for example no speech, a busy recognizer or a network error)
 * and the user can simply try again.
 */
internal fun asrRecordingErrorCause(
  error: AsrServiceError,
  installsLanguagePacks: Boolean,
): SpeechEngineErrorCause? =
  when (error) {
    is AsrServiceError.ModelNotDownloaded ->
      if (installsLanguagePacks) {
        SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED
      } else {
        SpeechEngineErrorCause.OTHER
      }
    is AsrServiceError.ModelInitializationFailed,
    is AsrServiceError.HardwareNotSupported -> SpeechEngineErrorCause.OTHER
    else -> null
  }
