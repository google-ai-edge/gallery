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

import android.graphics.Bitmap
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.MultilingualTtsSegment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PresynthesizedFillerCache
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SelectedSpeechEngineLocales
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherChatTurn
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageAdjustment
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageOptions
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageResolution
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherOverlayState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TtsPlaybackSource
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDictionary
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherPreferencesStore
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherScreenName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.defaultLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.defaultMainLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.orDefaultLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.orDefaultMainLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.PcmAudioStreamPlayer
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.PreparedTtsKeys
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.cancelAsrRecording
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.isAsrRecordingOrPending
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareAsrEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.prepareTtsEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.resolveNativeSpokenPhrases
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.speakSegments
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.stopTtsPlayback
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.isTeacherSubsystemWarming
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr.XrGlassesManager
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SpeechEngineError
import com.google.ai.edge.gallery.data.SpeechEngineErrorCause
import com.google.ai.edge.gallery.data.SpeechEngineKind
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.di.ApplicationScope
import com.google.ai.edge.gallery.di.DefaultDispatcher
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.Dictionary
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsService
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal const val TAG = "AGXrLanguageTeacherVM"
internal const val ASR_FINALIZE_TIMEOUT_MS = 2_000L
/** Key for the `AsrModelIdentifier` the user explicitly picked for the XR teacher. */
internal const val ASR_MODEL_ID_KEY = "xr_teacher_asr_model_id"
/** Key for the [TtsModelIdentifier] the user explicitly picked for the XR teacher. */
internal const val TTS_MODEL_ID_KEY = "xr_teacher_tts_model_id"
internal const val ASR_ERROR_NO_SPEECH = "No speech detected"

/**
 * A line the teacher shows and speaks.
 *
 * @property text The line to show and speak.
 * @property languageTag The BCP-47 locale the TTS engine should speak [text] in.
 */
data class SpokenLine(val text: String, val languageTag: String)

/** The selections that the text-to-speech engine is prepared for; a change prepares it again. */
internal data class TtsInputs(
  val ttsModelId: String,
  val mainLanguage: Language,
  val learnLanguage: Language,
  val persona: CharacterPersona,
)

data class XrLanguageTeacherUiState(
  val currentScreen: String = XrLanguageTeacherScreenName.CAMERA.value,
  val isSessionActive: Boolean = false,
  val isRunningInference: Boolean = false,
  val isWaitingForFirstToken: Boolean = false,
  /**
   * True from the moment a spoken question is sent (mic stop / glasses tap 2) until the model call
   * actually starts. The XR status line shows "Thinking…" during this gap.
   */
  val isSubmittingToModel: Boolean = false,
  /**
   * Reason the last ASR session ended unexpectedly, or blank. While set and idle, the XR status
   * line asks the user to tap to retry.
   */
  val lastAsrError: String = "",
  val overlayState: TeacherOverlayState = TeacherOverlayState.NONE,
  val capturedBitmap: Bitmap? = null,
  val vocabCardData: VocabCardData? = null,
  val isExtractingCard: Boolean = false,
  val vocabCards: List<VocabCardData> = emptyList(),
  val currentVocabCardIndex: Int = 0,
  val isCurrentCardSaved: Boolean = false,
  val isRecordingAsr: Boolean = false,
  val liveAsrTranscript: String = "",
  val confirmedTranscript: String = "",
  val interimTranscript: String = "",
  val lastSpokenQuestion: String? = null,
  val chatTurns: List<TeacherChatTurn> = emptyList(),
  /**
   * The teacher's opening line for the current session and the locale to speak it in, shown before
   * the first question and as the first entry of the history sheet. `null` until the camera greets
   * the user. It is kept out of [chatTurns] so that it is never sent to the model.
   */
  val sessionGreetingLine: SpokenLine? = null,
  val isSpeakingDialogue: Boolean = false,
  val isSpeakingVocabPhrase: Boolean = false,
  /** The vocabulary word or sentence the user asked to hear, while it loads or plays. */
  val phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
  val isSpeakingTts: Boolean = false,
  val teacherDialogue: String = "",
  val asrEngine: SpeechEngine = SpeechEngine.SYSTEM_ASR,
  val asrReadiness: SpeechEngineReadiness = SpeechEngineReadiness.WARMING,
  /** Why the speech recognition engine couldn't start, while [asrReadiness] is `ERROR`. */
  val asrErrorCause: SpeechEngineErrorCause? = null,
  val ttsEngine: SpeechEngine = SpeechEngine.PLATFORM_TTS,
  val ttsReadiness: SpeechEngineReadiness = SpeechEngineReadiness.WARMING,
  /** Why the text-to-speech engine couldn't start, while [ttsReadiness] is `ERROR`. */
  val ttsErrorCause: SpeechEngineErrorCause? = null,
  /**
   * Whether the user dismissed the speech engine error popup. It shows again on the next failure,
   * on retry, or when the user taps the microphone.
   */
  val isSpeechEngineErrorDismissed: Boolean = false,
  val personaPreviewState: PersonaPreviewState = PersonaPreviewState.Idle,
  val audioLevel: Float = 0f,
  val activeSpokenText: String = "",
  val isXrMode: Boolean = false,
  val isGlassesConnected: Boolean = false,
  val errorMessage: String? = null,
) {
  /** The text of [sessionGreetingLine], or blank until the camera greets the user. */
  val sessionGreeting: String
    get() = sessionGreetingLine?.text.orEmpty()

  /**
   * Whether the live camera feed is frozen on a captured frame or active lesson session rather than
   * streaming live preview frames.
   */
  val isCameraFrozen: Boolean
    get() = isSessionActive || isRunningInference || capturedBitmap != null

  /**
   * Whether the live camera preview should actively run rather than being paused (frozen for a
   * lesson turn, warming up a teacher subsystem, or covered by the first-time onboarding dialog).
   */
  fun isLiveCameraFeedActive(
    isModelInitializing: Boolean,
    isOnboardingVisible: Boolean = false,
  ): Boolean =
    !isCameraFrozen &&
      !isOnboardingVisible &&
      !isTeacherSubsystemWarming(
        isModelInitializing = isModelInitializing,
        asrReadiness = asrReadiness,
        ttsReadiness = ttsReadiness,
      )

  /** Whether a selected speech engine couldn't start or failed, which pauses the lesson. */
  val hasSpeechEngineError: Boolean
    get() =
      asrReadiness == SpeechEngineReadiness.ERROR || ttsReadiness == SpeechEngineReadiness.ERROR

  /**
   * The speech engine error popup to show, or `null`. A speech recognition error is shown before a
   * voice error.
   */
  val speechEngineError: SpeechEngineError?
    get() =
      when {
        isSpeechEngineErrorDismissed -> null
        asrReadiness == SpeechEngineReadiness.ERROR ->
          SpeechEngineError(
            kind = SpeechEngineKind.SPEECH_RECOGNITION,
            cause = asrErrorCause ?: SpeechEngineErrorCause.OTHER,
          )
        ttsReadiness == SpeechEngineReadiness.ERROR ->
          SpeechEngineError(
            kind = SpeechEngineKind.VOICE,
            cause = ttsErrorCause ?: SpeechEngineErrorCause.OTHER,
          )
        else -> null
      }
}

@HiltViewModel
class XrLanguageTeacherViewModel
@Inject
constructor(
  @XrLanguageTeacherDictionary internal val dictionaryDataStore: DataStore<Dictionary>,
  internal val storageRepository: XrLanguageTeacherPreferencesStore,
  internal val asrServiceProvider: AsrServiceProvider,
  internal val ttsServiceProvider: TtsServiceProvider,
  internal val asrSettingsRepository: AsrSettingsRepository,
  internal val ttsSettingsRepository: TtsSettingsRepository,
  internal val speechSelectionUseCase: SpeechSelectionUseCase,
  internal val chatSessionRepository: ChatSessionRepository,
  internal val pcmAudioStreamPlayer: PcmAudioStreamPlayer,
  internal val xrGlassesManager: XrGlassesManager,
  @DefaultDispatcher internal val inferenceDispatcher: CoroutineDispatcher,
  @IoDispatcher internal val ioDispatcher: CoroutineDispatcher,
  @ApplicationScope
  internal val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) : ViewModel() {

  internal val _uiState = MutableStateFlow(XrLanguageTeacherUiState())
  val uiState: StateFlow<XrLanguageTeacherUiState> = _uiState.asStateFlow()

  internal fun updateUiState(transform: (XrLanguageTeacherUiState) -> XrLanguageTeacherUiState) {
    _uiState.update(transform)
  }

  /**
   * Shows the card placeholder for the turn [turnEpoch] while Pass 2 extracts its card, unless Stop
   * or a reset has ended that turn since: they clear the flag, and nothing would clear it again.
   */
  internal fun markExtractingCardIfCurrent(turnEpoch: Long) {
    _uiState.update {
      if (sessionEpoch.get() == turnEpoch) it.copy(isExtractingCard = true) else it
    }
  }

  internal fun setPersonaPreviewState(state: PersonaPreviewState) {
    _uiState.update { it.copy(personaPreviewState = state) }
  }

  /**
   * Marks the speech recognition engine as warming up, showing [engine] in the HUD when given, so
   * that the lesson controls stay disabled until it is ready. Clears its last error so that the
   * next failure shows the error popup again.
   */
  internal fun markAsrWarming(engine: SpeechEngine? = null) {
    _uiState.update {
      it.copy(
        asrEngine = engine ?: it.asrEngine,
        asrReadiness = SpeechEngineReadiness.WARMING,
        asrErrorCause = null,
        isSpeechEngineErrorDismissed = false,
      )
    }
  }

  /**
   * Marks the text-to-speech engine as warming up, showing [engine] in the HUD when given, so that
   * the lesson controls stay disabled until it is ready. Clears its last error so that the next
   * failure shows the error popup again.
   */
  internal fun markTtsWarming(engine: SpeechEngine? = null) {
    _uiState.update {
      it.copy(
        ttsEngine = engine ?: it.ttsEngine,
        ttsReadiness = SpeechEngineReadiness.WARMING,
        ttsErrorCause = null,
        isSpeechEngineErrorDismissed = false,
      )
    }
  }

  internal val _systemPromptOverride = MutableStateFlow(storageRepository.getSystemPromptOverride())
  /**
   * User-edited replacement for the whole system instruction, or `null` to use
   * [buildDefaultSystemPrompt].
   */
  val systemPromptOverride: StateFlow<String?> = _systemPromptOverride.asStateFlow()

  internal val _personaPromptTemplateVersion = MutableStateFlow(0)
  /** Incremented whenever a persona prompt template is customized or reset. */
  val personaPromptTemplateVersion: StateFlow<Int> = _personaPromptTemplateVersion.asStateFlow()

  internal var activeAsrService: AsrService? = null
  internal var asrAmplitudeJob: Job? = null

  /** The `AsrModelIdentifier` of the global ASR engine. */
  val selectedAsrModelID: StateFlow<String>
    get() = asrSettingsRepository.selectedModelId

  /**
   * The [AsrEngineType] that serves [selectedAsrModelID], or `null` if no engine serves it.
   * Allowlist speech recognition models (for example Parakeet) resolve to
   * [AsrEngineType.SPEECH_PROCESSOR], like in AI Chat.
   */
  internal val selectedAsrEngineType: AsrEngineType?
    get() = AsrModelRegistry.resolveEngineType(selectedAsrModelID.value)

  /**
   * The [TtsModelIdentifier] of the global TTS engine. XR Language Teacher only uses that engine
   * and never falls back to another one.
   */
  val selectedTtsModelID: StateFlow<String>
    get() = ttsSettingsRepository.selectedTtsModelID

  /** Whether this ViewModel started glasses connection monitoring. */
  internal val isMonitoringGlassesConnection: Boolean = true

  init {
    if (isMonitoringGlassesConnection) {
      xrGlassesManager.startMonitoringConnection()
      viewModelScope.launch {
        xrGlassesManager.isGlassesConnected.collect { connected ->
          _uiState.update { it.copy(isGlassesConnected = connected) }
          if (!connected && _uiState.value.isXrMode) {
            switchToPhone(isDisconnect = true)
          }
        }
      }
    }
  }

  internal val sessionEpoch = AtomicLong(0L)
  internal var activeInferenceJob: Job? = null
  @Volatile internal var isConversationInitialized: Boolean = false
  @Volatile internal var hasPendingPreferencePrewarm: Boolean = false
  @Volatile internal var activeSessionModelName: String? = null
  @Volatile internal var activeSessionModelInstance: Any? = null
  /** System instruction text bound to the active conversation, if initialized. */
  @Volatile internal var activeSystemPromptText: String? = null
  /** Number of user turns sent on the currently initialized conversation. */
  @Volatile internal var activeConversationTurnCount: Int = 0
  /** Last known model instance observed from screen or turn lifecycle for background pre-warm. */
  @Volatile internal var latestKnownModel: Model? = null
  /** Background job scaling the captured session bitmap and/or pre-warming a clean conversation. */
  @Volatile internal var activePrewarmJob: Job? = null
  /** Model used by the most recent lesson turn, so [onCleared] can stop native generation. */
  @Volatile internal var activeInferenceModel: Model? = null
  /** Background job running Pass 2 vocabulary card extraction. */
  @Volatile internal var activePass2Job: Job? = null
  @Volatile internal var turnStartTeacherDialogue: String = ""

  internal val sessionSaveMutex = Mutex()
  internal val dictionaryFileMutex = Mutex()
  internal val deletedSessionIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
  @Volatile internal var currentSessionIdInternal: String = UUID.randomUUID().toString()
  val currentSessionId: String
    get() = currentSessionIdInternal

  @Volatile internal var currentSessionStartTimestampMs: Long = 0L
  @Volatile internal var currentSessionImagePath: String? = null

  internal var prepareSpeechJob: Job? = null
  internal var prepareTtsJob: Job? = null
  internal var asrRecordingJob: Job? = null
  internal val asrSessionEpoch = AtomicLong(0L)
  @Volatile internal var isStoppingAsr: Boolean = false

  internal var ttsPlaybackJob: Job? = null
  internal val ttsPlaybackEpoch = AtomicLong(0L)
  /**
   * The engine stop launched by the latest `stopTtsPlayback`. The next playback waits for it, so
   * that a late stop can't cut off the new playback.
   */
  @Volatile internal var ttsStopJob: Job? = null
  /**
   * The TTS (model, language, voice) combinations prepared so far, to skip preparing them again.
   */
  internal val preparedTtsKeys = PreparedTtsKeys()
  /** The thinking fillers synthesized ahead of time for the current voice. */
  internal val presynthesizedFillers = PresynthesizedFillerCache()
  /** Background job synthesizing the thinking fillers while the teacher is idle. */
  @Volatile internal var fillerPresynthesisJob: Job? = null
  /** The TTS engine [fillerPresynthesisJob] synthesizes with. */
  @Volatile internal var fillerPresynthesisService: TtsService? = null
  /**
   * Whether a Pass 2 inference is generating. Its job returns as soon as the inference starts, and
   * `isExtractingCard` clears early on a null card, so neither tells when the engine is free.
   */
  @Volatile internal var isPass2InFlight: Boolean = false
  /** The thinking filler spoken last, so the next turn picks another one. */
  @Volatile internal var lastFillerText: String? = null

  /** Coordinates HUD greeting surfacing across session resets and preference updates. */
  internal val hudController = XrLanguageTeacherHudController()

  /** Whether camera access is currently denied or not yet granted on the Camera tab. */
  @Volatile internal var isCameraAccessDenied: Boolean = false

  /**
   * Updates whether camera access is denied (or not yet granted) on the Camera tab, stopping any
   * idle greeting speech when camera access is off.
   */
  fun setCameraAccessDenied(denied: Boolean) {
    isCameraAccessDenied = denied
    if (denied && !_uiState.value.isSessionActive) {
      hudController.onSessionDismissed()
      stopTtsPlayback()
    }
  }

  val historySessions: StateFlow<List<ChatSessionProto>> =
    chatSessionRepository.chatSessions
      .map { sessions ->
        sessions
          .filter { it.taskId == BuiltInTaskId.LLM_XR_LANGUAGE_TEACHER }
          .sortedByDescending { it.timestampMs }
      }
      .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

  val dictionaryEntries: StateFlow<List<DictionaryEntry>> =
    dictionaryDataStore.data
      .map { it.entriesList }
      .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

  /**
   * The native language the student saved. While they have not picked one, this is
   * [defaultMainLanguage], which follows the system language and is not persisted.
   */
  internal val savedMainLanguage: StateFlow<Language> =
    dictionaryDataStore.data
      .map { it.mainLanguage.orDefaultMainLanguage() }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = defaultMainLanguage(),
      )

  /**
   * The language the student saved to learn. While they have not picked one, this is
   * [defaultLearnLanguage] for [savedMainLanguage], so never their native language; that default is
   * not persisted.
   */
  internal val savedLearnLanguage: StateFlow<Language> =
    dictionaryDataStore.data
      .map { it.learnLanguage.orDefaultLearnLanguage(it.mainLanguage.orDefaultMainLanguage()) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = defaultLearnLanguage(defaultMainLanguage()),
      )

  /** Whether the user explicitly saved languages in DataStore. */
  private val isLanguageExplicitlySaved: StateFlow<Boolean> =
    dictionaryDataStore.data
      .map {
        it.mainLanguage != Language.LANGUAGE_UNSPECIFIED ||
          it.learnLanguage != Language.LANGUAGE_UNSPECIFIED
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val selectedPersona: StateFlow<CharacterPersona> =
    dictionaryDataStore.data
      .map { CharacterPersona.fromId(it.selectedPersona) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CharacterPersona.BUDDY,
      )

  /**
   * The selected ASR and TTS engines with the locale tags each supports, which the Teacher
   * preferences form uses to check and download the language packs those engines need.
   */
  val selectedSpeechEngineLocales: StateFlow<SelectedSpeechEngineLocales> =
    createSelectedSpeechEngineLocalesFlow()

  /**
   * The options for the native language and the language to learn ([TeacherLanguageOptions]), from
   * the models' allowlist entries.
   *
   * Recomputed when a model changes, when the allowlist registers the models, and when an engine
   * finishes preparing, because the system engines only list their languages once initialized.
   */
  val languageOptions: StateFlow<TeacherLanguageOptions> =
    createLanguageOptionsFlow(selectedSpeechEngineLocales)

  /** The saved languages and the ones the teacher uses with the selected models. */
  internal val teacherLanguages: StateFlow<TeacherLanguageResolution> =
    combine(savedMainLanguage, savedLearnLanguage, languageOptions) { main, learn, options ->
        val saved = TeacherLanguages(main = main, learn = learn)
        TeacherLanguageResolution(saved, options.resolveLanguages(saved))
      }
      .distinctUntilChanged()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue =
          run {
            val saved = TeacherLanguages(savedMainLanguage.value, savedLearnLanguage.value)
            TeacherLanguageResolution(saved, languageOptions.value.resolveLanguages(saved))
          },
      )

  /**
   * The student's native language as the teacher uses it: the saved one, or a replacement while the
   * selected models don't support it ([languageAdjustment]).
   */
  val mainLanguage: StateFlow<Language> =
    teacherLanguages
      .map { it.used.main }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = teacherLanguages.value.used.main,
      )

  /**
   * The language the student learns as the teacher uses it: the saved one, or a replacement while
   * the selected models don't support it ([languageAdjustment]). Never [mainLanguage].
   */
  val learnLanguage: StateFlow<Language> =
    teacherLanguages
      .map { it.used.learn }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = teacherLanguages.value.used.learn,
      )

  /**
   * Set while the selected models don't support a saved language, so the teacher uses another one.
   * The saved choice is kept, and replaced only when the student confirms the preferences
   * ([applyTeacherPreferences]). `null` until the allowlist registers the models, so nothing is
   * reported while their languages aren't known yet.
   */
  val languageAdjustment: StateFlow<TeacherLanguageAdjustment?> =
    combine(teacherLanguages, languageOptions, isLanguageExplicitlySaved) { res, options, isSaved ->
        if (!isSaved) null else options.languageAdjustment(res.saved)
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

  private val acknowledgedLanguageAdjustment = MutableStateFlow<TeacherLanguageAdjustment?>(null)

  /**
   * The [languageAdjustment] to tell the student about, or `null` once they have seen it
   * ([acknowledgeLanguageNotice]). A different adjustment is reported again.
   */
  val languageNotice: StateFlow<TeacherLanguageAdjustment?> =
    combine(languageAdjustment, acknowledgedLanguageAdjustment) { adjustment, acknowledged ->
        adjustment?.takeIf { it != acknowledged }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

  /** Records that the student has seen [languageNotice]. */
  fun acknowledgeLanguageNotice(adjustment: TeacherLanguageAdjustment) {
    acknowledgedLanguageAdjustment.value = adjustment
  }

  /** The languages that can be learned with the selected TTS model. */
  val availableLanguages: StateFlow<List<Language>> =
    languageOptions
      .map { it.learn }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TeacherLanguageOptions.UNRESTRICTED.learn,
      )

  /**
   * The native languages the selected models support: those both the ASR model (the teacher hears
   * them) and the TTS model (the teacher speaks them) support.
   */
  val availableNativeLanguages: StateFlow<List<Language>> =
    languageOptions
      .map { it.native }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TeacherLanguageOptions.UNRESTRICTED.native,
      )

  init {
    observeLanguageAndSpeechInputs()
  }

  fun setScreen(screen: String) {
    val previousScreen = _uiState.value.currentScreen
    if (previousScreen != screen) {
      stopTtsPlayback()
      if (isAsrRecordingOrPending) {
        cancelAsrRecording()
      }
    }
    _uiState.update { it.copy(currentScreen = screen) }
  }

  /**
   * Called when the app leaves the foreground (Home, another app, or screen off). Cancels ASR
   * recording, discarding its transcript, and stops TTS playback, so nothing records or plays in
   * the background.
   *
   * Does nothing in glasses mode ([XrLanguageTeacherUiState.isXrMode]): the user talks and listens
   * through the glasses there, so the phone screen being off or showing another app is expected.
   */
  fun onAppBackgrounded() {
    if (_uiState.value.isXrMode) return
    if (isAsrRecordingOrPending) {
      cancelAsrRecording()
    }
    stopTtsPlayback()
  }

  val glassesCameraBitmap: StateFlow<Bitmap?> = xrGlassesManager.glassesCameraBitmap

  /** True between the "finish speech" glasses tap and the start of the resulting lesson turn. */
  @Volatile internal var isSubmittingXrTurn: Boolean = false

  /**
   * Shows and speaks the teacher's greeting when the user first opens the feature.
   *
   * Waits until ASR and TTS are ready before showing or speaking the greeting, so it also waits
   * while a speech engine error pauses the lesson. Does nothing once the teacher has greeted once,
   * so starting a new session ("Ask about something else") or switching tabs does not repeat the
   * greeting. The greeting is not sent to the model.
   */
  fun greetNewSessionIfNeeded() {
    if (isCameraAccessDenied) return
    val state = _uiState.value
    val shouldGreet =
      hudController.consumeGreetingGate(
        isSessionActive = state.isSessionActive,
        isRecordingAsr = isAsrRecordingOrPending,
        currentGreeting = state.sessionGreeting,
        areSpeechEnginesReady = areSpeechEnginesReady(state),
      )
    if (!shouldGreet) return
    val spokenPhrases = resolveNativeSpokenPhrases()
    val greeting = SpokenLine(spokenPhrases.phrases.greeting, spokenPhrases.languageTag)
    _uiState.update { it.copy(sessionGreetingLine = greeting) }
    playSessionGreeting()
  }

  internal fun completePendingGreetingIfReady() {
    if (
      !isCameraAccessDenied &&
        hudController.shouldCompletePendingGreeting(areSpeechEnginesReady(_uiState.value))
    ) {
      greetNewSessionIfNeeded()
    }
  }

  internal fun areSpeechEnginesReady(state: XrLanguageTeacherUiState): Boolean =
    state.asrReadiness == SpeechEngineReadiness.READY &&
      state.ttsReadiness == SpeechEngineReadiness.READY

  /**
   * Speaks the current session greeting with the persona's voice, in the language it is shown in:
   * the native language, or English when the TTS engine had no native-language voice.
   */
  fun playSessionGreeting() {
    if (isCameraAccessDenied && !_uiState.value.isSessionActive) return
    val greeting = _uiState.value.sessionGreetingLine ?: return
    if (greeting.text.isBlank()) return
    val segments =
      listOf(
        MultilingualTtsSegment(
          text = greeting.text,
          languageCode = greeting.languageTag,
          isTargetLanguage = false,
        )
      )
    speakSegments(segments, selectedPersona.value, source = TtsPlaybackSource.DIALOGUE)
  }

  // MARK: - Speech Engine Readiness

  /** Hides the speech engine error popup. The lesson stays paused until the engine works. */
  fun dismissSpeechEngineError() {
    _uiState.update { it.copy(isSpeechEngineErrorDismissed = true) }
  }

  /** Shows the speech engine error popup again, for example when the user taps the microphone. */
  fun reshowSpeechEngineError() {
    _uiState.update { it.copy(isSpeechEngineErrorDismissed = false) }
  }

  /** Tries to start the failed speech engine of [kind] again, when the user taps Retry. */
  fun retrySpeechEngine(kind: SpeechEngineKind) {
    _uiState.update { it.copy(isSpeechEngineErrorDismissed = false) }
    when (kind) {
      SpeechEngineKind.SPEECH_RECOGNITION -> prepareAsrEngine()
      SpeechEngineKind.VOICE -> prepareTtsEngine()
    }
  }

  fun appendChatTurn(isUser: Boolean, text: String) {
    if (text.isBlank()) return
    _uiState.update {
      it.copy(chatTurns = it.chatTurns + TeacherChatTurn(isUser = isUser, text = text))
    }
  }

  fun clearChatTurns() {
    _uiState.update { it.copy(chatTurns = emptyList(), lastSpokenQuestion = null) }
  }

  // MARK: - Bilingual & Fallback TTS Playback

  @Volatile internal var ttsChannel: Channel<MultilingualTtsSegment>? = null

  public override fun onCleared() {
    performOnCleared()
  }

  companion object {
    internal fun parsePass2CardOutput(
      rawPass2Text: String,
      replyWithTargets: String = "",
      mainLanguage: Language = Language.LANGUAGE_ENGLISH,
      learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
    ): VocabCardData? =
      parsePass2CardOutputImpl(
        rawPass2Text = rawPass2Text,
        replyWithTargets = replyWithTargets,
        mainLanguage = mainLanguage,
        learnLanguage = learnLanguage,
      )

    internal fun buildPass2CardExtractionPrompt(
      mainLangName: String,
      learnLangName: String,
      teacherReplyWithTags: String,
      spokenQuestion: String? = null,
      existingWords: List<String> = emptyList(),
    ): String =
      buildPass2CardExtractionPromptImpl(
        mainLangName = mainLangName,
        learnLangName = learnLangName,
        teacherReplyWithTags = teacherReplyWithTags,
        spokenQuestion = spokenQuestion,
        existingWords = existingWords,
      )

    internal fun buildPass1Prompt(
      persona: CharacterPersona,
      mainLangName: String,
      learnLangName: String,
      spokenQuestion: String? = null,
      focalSubject: String? = null,
      isFollowUp: Boolean = false,
      history: List<TeacherChatTurn> = emptyList(),
    ): String =
      buildPass1PromptImpl(
        persona = persona,
        mainLangName = mainLangName,
        learnLangName = learnLangName,
        spokenQuestion = spokenQuestion,
        focalSubject = focalSubject,
        isFollowUp = isFollowUp,
        history = history,
      )
  }
}
