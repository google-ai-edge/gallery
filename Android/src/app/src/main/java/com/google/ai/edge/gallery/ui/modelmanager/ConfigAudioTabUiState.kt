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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.data.Model

/**
 * Everything the Config side sheet's Audio tab renders.
 *
 * Built outside composables by [buildConfigAudioTabUiState] so `ConfigDialog` only renders state
 * without branching on engine types or importing engine registries.
 *
 * @property asr Speech recognition (ASR) section, or `null` when the task has no speech input.
 * @property tts Read-aloud (TTS) section, or `null` when the task has no read-aloud.
 */
data class ConfigAudioTabUiState(
  val asr: ConfigAsrSectionUiState? = null,
  val tts: ConfigTtsSectionUiState? = null,
) {
  /** Whether the Config side sheet shows the Audio tab. */
  val isVisible: Boolean
    get() = asr != null || tts != null
}

/**
 * Text shown in the Audio tab, resolved from a string resource or a literal model/voice display
 * name.
 */
sealed interface ConfigAudioText {
  /** A plain string resource. */
  data class Resource(@StringRes val resId: Int) : ConfigAudioText

  /**
   * A formatted string resource where each argument may be another [ConfigAudioText] or a primitive
   * value.
   */
  data class Formatted(@StringRes val resId: Int, val args: List<Any>) : ConfigAudioText {
    constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
  }

  /** A literal display string (such as an allowlist model or voice display name). */
  data class Literal(val text: String) : ConfigAudioText
}

/**
 * Speech recognition (ASR) section of the Config side sheet's Audio tab.
 *
 * Rendered in order:
 * 1. ASR model dropdown ([selectedModelLabel], [modelItems])
 * 2. Status row ([statusRow])
 * 3. Language dropdown ([languageDropdown], `null` when hidden for the task)
 * 4. "Show live transcription" toggle ([showLiveTranscriptionToggle], [liveTranscriptionEnabled])
 */
data class ConfigAsrSectionUiState(
  val selectedModelLabel: ConfigAudioText,
  val modelItems: List<ConfigAudioModelDropdownItemUiState> = emptyList(),
  val statusRow: ConfigAudioStatusRowUiState? = null,
  val languageDropdown: ConfigAudioLanguageDropdownUiState? = null,
  val showLiveTranscriptionToggle: Boolean = false,
  val liveTranscriptionEnabled: Boolean = true,
)

/**
 * Read-aloud (TTS) section of the Config side sheet's Audio tab.
 *
 * Rendered in order:
 * 1. TTS model dropdown ([selectedModelLabel], [modelItems])
 * 2. Status row ([statusRow])
 * 3. Language dropdown ([languageDropdown], `null` when hidden for the task)
 * 4. Voice dropdown ([voiceDropdown])
 * 5. Auto-play TTS toggle ([showAutoPlayToggle], [autoPlayEnabled])
 */
data class ConfigTtsSectionUiState(
  val selectedModelLabel: ConfigAudioText,
  val modelItems: List<ConfigAudioModelDropdownItemUiState> = emptyList(),
  val statusRow: ConfigAudioStatusRowUiState? = null,
  val languageDropdown: ConfigAudioLanguageDropdownUiState? = null,
  val voiceDropdown: ConfigTtsVoiceDropdownUiState,
  val showAutoPlayToggle: Boolean = false,
  val autoPlayEnabled: Boolean = false,
)

/** Opaque selection target for an item in the ASR or TTS model dropdown. */
sealed interface ConfigAudioModelSelectionTarget {
  /** Selects the platform built-in ASR recognizer. */
  data object SystemAsr : ConfigAudioModelSelectionTarget

  /** Selects a built-in ASR engine identified by [engineName] (or [model] when present). */
  data class AsrModel(val engineName: String, val model: Model? = null) :
    ConfigAudioModelSelectionTarget

  /** Selects the platform built-in Android TTS engine. */
  data object SystemTts : ConfigAudioModelSelectionTarget

  /** Selects a TTS engine identified by [engineId] and backed by [model]. */
  data class TtsModel(val engineId: String, val model: Model) : ConfigAudioModelSelectionTarget
}

/**
 * One item in the ASR or TTS model dropdown.
 *
 * Items follow the `selectable ⇔ Ready` rule: only [ConfigAudioModelItemStatus.Ready] items are
 * selectable ([isSelectable] is `true`). Downloaded [ConfigAudioModelItemStatus.Ready] models show
 * a trailing downloaded badge, while built-in system options show no trailing badge.
 */
data class ConfigAudioModelDropdownItemUiState(
  val key: String,
  val label: ConfigAudioText,
  val target: ConfigAudioModelSelectionTarget,
  val isSelected: Boolean,
  val status: ConfigAudioModelItemStatus,
) {
  /**
   * An item is selectable in the dropdown if and only if its state is
   * [ConfigAudioModelItemStatus.Ready].
   */
  val isSelectable: Boolean
    get() = status is ConfigAudioModelItemStatus.Ready
}

/** Availability / download status of an item in the ASR or TTS model dropdown. */
sealed interface ConfigAudioModelItemStatus {
  /**
   * Ready to use; rendered as a selectable item (with a trailing downloaded badge for non-built-in
   * models).
   */
  data object Ready : ConfigAudioModelItemStatus

  /** Not downloaded yet; rendered greyed out with a trailing download button for [model]. */
  data class Downloadable(val model: Model) : ConfigAudioModelItemStatus

  /**
   * Currently downloading; rendered greyed out with a progress ring and a progress/background
   * subtitle.
   */
  data class Downloading(
    val receivedBytes: Long,
    val totalBytes: Long,
    val continuesInBackground: Boolean,
  ) : ConfigAudioModelItemStatus

  /** Queued by the system; rendered greyed out with a progress ring and a "Queued" subtitle. */
  data object Queued : ConfigAudioModelItemStatus

  /** Availability is being checked; rendered greyed out with a progress ring. */
  data object Checking : ConfigAudioModelItemStatus

  /**
   * Cannot be selected right now (needs update, opt-in, language pack, unavailable, check failed,
   * or engine error); rendered greyed out with a one-line [reasonRes] subtitle.
   */
  data class Unavailable(@StringRes val reasonRes: Int) : ConfigAudioModelItemStatus
}

/** Visual tone of the status indicator dot below the ASR or TTS model dropdown. */
enum class ConfigAudioStatusTone {
  /** Primary color dot (ready / built-in). */
  READY,

  /** Tertiary color dot (initializing / downloading / checking / queued). */
  PROGRESS,

  /** Error color dot (unavailable / failed). */
  ERROR,

  /** Neutral onSurfaceVariant dot (not downloaded). */
  NEUTRAL,
}

/** The status line shown directly below the ASR or TTS model dropdown. */
data class ConfigAudioStatusRowUiState(val tone: ConfigAudioStatusTone, val text: ConfigAudioText)

/**
 * Language dropdown shown in the ASR or TTS section.
 *
 * @property selectedLanguageCode Currently selected BCP-47 language tag.
 * @property items Language options in display order.
 * @property isSelectionEnabled `true` when the user can pick a language; `false` when the list is
 *   read-only (such as Parakeet, which auto-detects the spoken language).
 * @property supportsPerLanguageDownload `true` when individual languages are downloaded as separate
 *   packs (ML Kit ASR Basic and ML Kit TTS).
 * @property model The supplementary [Model] that owns the language packs when
 *   [supportsPerLanguageDownload] is `true`.
 * @property descriptionRes Optional description / `*` note shown below the Language title.
 */
data class ConfigAudioLanguageDropdownUiState(
  val selectedLanguageCode: String,
  val items: List<ConfigAudioLanguageItemUiState>,
  val isSelectionEnabled: Boolean,
  val supportsPerLanguageDownload: Boolean,
  val model: Model? = null,
  @StringRes val descriptionRes: Int? = null,
  val description: ConfigAudioText? = descriptionRes?.let { ConfigAudioText.Resource(it) },
  val showInfoTooltip: Boolean = supportsPerLanguageDownload,
)

/**
 * Returns these languages as rows of the shared "Supported languages" list, in the same order.
 * Installed rows are radio buttons only when the user can pick a language.
 */
fun ConfigAudioLanguageDropdownUiState.toSupportedLanguagesUiState():
  SupplementaryLanguagesUiState =
  SupplementaryLanguagesUiState(
    rows =
      items.map { item ->
        SupplementaryLanguageRowUiState(
          languageCode = item.languageCode,
          status = item.packStatus.toSupplementaryLanguageStatus(),
          isSelectable = isSelectionEnabled && item.isSelectable,
          isSelected = isSelectionEnabled && item.isSelected,
        )
      },
    showInfoTooltip = showInfoTooltip,
  )

/**
 * One item in a [ConfigAudioLanguageDropdownUiState].
 *
 * @property languageCode BCP-47 language tag.
 * @property isSelected Whether this language is the current choice.
 * @property isSelectable Whether tapping this item selects it (only `true` when the dropdown allows
 *   selection and the language is installed).
 * @property packStatus Pack download status for engines with per-language downloads, or
 *   [LanguagePackUiStatus.Installed] when all languages come with the model.
 */
data class ConfigAudioLanguageItemUiState(
  val languageCode: String,
  val isSelected: Boolean,
  val isSelectable: Boolean,
  val packStatus: LanguagePackUiStatus = LanguagePackUiStatus.Installed,
)

/**
 * Voice dropdown shown in the TTS section, filtered by the selected TTS language.
 *
 * @property isLoading Whether the active TTS engine is still warming up / loading its voice list.
 * @property selectedItem The currently selected item in [items].
 * @property items Voice options for the selected language; the first item is always `"Default
 *   (<language>)"`.
 * @property engineId Identifier of the TTS engine that speaks voice previews in this dropdown.
 * @property voicePreviewUiState Current loading/playing voice preview state across TTS engines.
 */
data class ConfigTtsVoiceDropdownUiState(
  val isLoading: Boolean = false,
  val selectedItem: ConfigTtsVoiceItemUiState,
  val items: List<ConfigTtsVoiceItemUiState>,
  val engineId: String = "",
  val voicePreviewUiState: VoicePreviewUiState = VoicePreviewUiState(),
)

/**
 * One item in [ConfigTtsVoiceDropdownUiState].
 *
 * @property id Voice identifier persisted in settings (`"System Default"` for the default item).
 * @property displayName Display name of the voice when [isDefaultForLanguage] is `false`.
 * @property isDefaultForLanguage `true` for the first item (`"Default (<language>)"`).
 * @property languageCode BCP-47 language tag used to format `"Default (<language>)"`.
 */
data class ConfigTtsVoiceItemUiState(
  val id: String,
  val displayName: String = "",
  val isDefaultForLanguage: Boolean = false,
  val languageCode: String = "",
)

/** Callbacks from the Config side sheet's Audio tab. */
class ConfigAudioTabActions(
  val onSelectModel: (ConfigAudioModelSelectionTarget) -> Unit = {},
  val onDownloadModel: (Model) -> Unit = {},
  val onSelectAsrLanguage: (String) -> Unit = {},
  val onSelectTtsLanguage: (String) -> Unit = {},
  val onDownloadLanguagePack: (model: Model, languageCode: String) -> Unit = { _, _ -> },
  val onLanguageDropdownExpanded: (Model) -> Unit = {},
  val onLiveTranscriptionChanged: (Boolean) -> Unit = {},
  val onSelectTtsVoice: (String) -> Unit = {},
  val onToggleVoicePreview: (String) -> Unit = {},
  val onStopVoicePreview: () -> Unit = {},
  val onAutoPlayTtsChanged: (Boolean) -> Unit = {},
  val onRestoreDefaults: () -> Unit = {},
)
