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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.studyview

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PhrasePlaybackState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabGameMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabPopperEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabRunnerEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayLearnLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabGame
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabGameRequest
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabPopperViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherVocabRunnerViewModel
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.vocabGameEntryKey
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.StudyBackgroundShapes
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlin.random.Random

/** The card counts offered on the Flash cards start screen, besides all the cards. */
private val FLASHCARDS_CARD_COUNTS = listOf(5, 10, 20)

/** The card count picked on the Flash cards start screen until the player picks another. */
private const val FLASHCARDS_DEFAULT_CARD_COUNT = 10

/** The activities of the Study tab, each opened through its [VocabGameStartScreen]. */
internal enum class StudyActivity {
  FLASHCARDS,
  VOCAB_POPPER,
  VOCAB_RUNNER,
}

private const val isStudyGamesNonAsrEnabled: Boolean = true

private fun vocabPopperMinimumCount(mode: XrLanguageTeacherVocabGameMode): Int =
  when (mode) {
    XrLanguageTeacherVocabGameMode.TAP ->
      XrLanguageTeacherVocabPopperEngine.MINIMUM_TAP_VOCABULARY_COUNT
    XrLanguageTeacherVocabGameMode.SPEAK ->
      XrLanguageTeacherVocabPopperEngine.MINIMUM_VOCABULARY_COUNT
  }

private fun vocabRunnerMinimumCount(mode: XrLanguageTeacherVocabGameMode): Int =
  when (mode) {
    XrLanguageTeacherVocabGameMode.TAP ->
      XrLanguageTeacherVocabRunnerEngine.MINIMUM_TAP_STICKER_VOCABULARY_COUNT
    XrLanguageTeacherVocabGameMode.SPEAK ->
      XrLanguageTeacherVocabRunnerEngine.MINIMUM_STICKER_VOCABULARY_COUNT
  }

/**
 * The Study tab: a centered header and the activity cards ([FlashcardsStudyCard],
 * [VocabPopperStudyCard], and [VocabRunnerStudyCard]) over decorative background shapes
 * ([StudyBackgroundShapes]).
 *
 * Each card's open button opens the reusable [VocabGameStartScreen], where the player reads how it
 * works and picks the settings. "Start review" launches [FlashcardReviewScreen] with the picked
 * number of cards; "Play game" asks [onPrepareGame] to get the game's stickers ready (which may
 * offer to download the sticker segmentation model) and launches [VocabPopperScreen] or
 * [VocabRunnerScreen] once the caller reports it as [readyGame], if its start screen is still open.
 * Leaving an activity comes straight back to the Study cards. While [onConvertToStickers] is set, a
 * low-prominence "Convert images to stickers" button under the header calls it.
 *
 * Everything but the Study cards is reported through [onReviewModeChanged], so the caller can hide
 * its navigation meanwhile.
 *
 * @param vocabPopperViewModel runs `Vocab Popper` rounds; the game can't start without it.
 * @param vocabRunnerViewModel runs `Vocab Runner` runs; the game can't start without it.
 * @param vocabEntryKeysWithImages the [vocabGameEntryKey]s of the saved words that have an image,
 *   which `Vocab Runner` needs for its obstacles.
 * @param areVocabImagesChecked whether [vocabEntryKeysWithImages] has been checked at least once;
 *   while `false`, the `Vocab Runner` card neither opens nor shows that it is locked.
 * @param readyGame the game whose stickers are ready, which this screen starts and reports through
 *   [onGameStarted], or `null`.
 * @param onPrepareGame gets the stickers of a requested game ready, then reports it as [readyGame];
 *   `null` starts games right away.
 * @param onGameStarted called once [readyGame] was handled.
 * @param onCancelGamePreparation drops a game request when its start screen or this tab goes away,
 *   so it never starts a game later.
 * @param onConvertToStickers offers to download the sticker segmentation model or turn unsegmented
 *   saved photos into stickers, or `null` to hide the button (once the model is downloaded and all
 *   saved photos are segmented, or when no segmentation model is available).
 * @param isPreparingGame whether a requested game's stickers or prompt audio are still preparing,
 *   shown as a loading state on the `Play game` button
 * @param onPrewarmGame warms up a game's sprites and Tap-mode prompt audio in the background as
 *   soon as its start screen opens
 * @param onCancelGamePrewarm stops any background start-screen pre-warm when the selected settings
 *   cannot launch a game
 */
@Composable
fun StudyScreen(
  entries: List<DictionaryEntry>,
  modifier: Modifier = Modifier,
  onReviewModeChanged: (Boolean) -> Unit = {},
  onPlayPhrase: ((String, Language) -> Unit)? = null,
  loadVocabImageBitmap: suspend (String, Language) -> ImageBitmap? = { _, _ -> null },
  isTtsLanguageSupported: (Language) -> Boolean = { true },
  isAsrLanguageSupported: (Language) -> Boolean = { true },
  onOpenAudioSettings: (() -> Unit)? = null,
  onOpenAsrSettings: (() -> Unit)? = null,
  phrasePlaybackState: PhrasePlaybackState = PhrasePlaybackState.Idle,
  vocabPopperViewModel: XrLanguageTeacherVocabPopperViewModel? = null,
  vocabRunnerViewModel: XrLanguageTeacherVocabRunnerViewModel? = null,
  vocabEntryKeysWithImages: Set<String> = emptySet(),
  areVocabImagesChecked: Boolean = true,
  readyGame: XrLanguageTeacherVocabGameRequest? = null,
  onPrepareGame: ((XrLanguageTeacherVocabGameRequest) -> Unit)? = null,
  onGameStarted: () -> Unit = {},
  onCancelGamePreparation: () -> Unit = {},
  onConvertToStickers: (() -> Unit)? = null,
  isPreparingGame: Boolean = false,
  onPrewarmGame: ((XrLanguageTeacherVocabGameRequest) -> Unit)? = null,
  onCancelGamePrewarm: () -> Unit = {},
) {
  var reviewLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var openStartActivity by rememberSaveable { mutableStateOf<StudyActivity?>(null) }
  // Saved so a configuration change keeps the round its ViewModel keeps running.
  var activeGame by rememberSaveable { mutableStateOf<StudyActivity?>(null) }
  var flashcardsLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var flashcardsCardCount by rememberSaveable { mutableIntStateOf(FLASHCARDS_DEFAULT_CARD_COUNT) }
  var reviewSessionSeed by rememberSaveable { mutableIntStateOf(0) }
  var popperLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var popperMode by rememberSaveable { mutableStateOf(XrLanguageTeacherVocabGameMode.TAP) }
  var runnerLanguage by rememberSaveable { mutableStateOf<Language?>(null) }
  var runnerMode by rememberSaveable { mutableStateOf(XrLanguageTeacherVocabGameMode.TAP) }

  val isAwayFromCards = reviewLanguage != null || openStartActivity != null || activeGame != null
  LaunchedEffect(isAwayFromCards) { onReviewModeChanged(isAwayFromCards) }

  DisposableEffect(Unit) { onDispose { onReviewModeChanged(false) } }

  val closeActivity = {
    onCancelGamePreparation()
    activeGame = null
    openStartActivity = null
  }
  // A game request still pending when the Study tab goes away must not start a game later. A
  // configuration change (e.g. rotation) keeps it: the start screen comes back and starts it.
  val latestOnCancelGamePreparation by rememberUpdatedState(onCancelGamePreparation)
  val latestOnCancelGamePrewarm by rememberUpdatedState(onCancelGamePrewarm)
  val latestActiveGame by rememberUpdatedState(activeGame)
  val activity = LocalActivity.current
  DisposableEffect(activity) {
    onDispose { if (activity?.isChangingConfigurations != true) latestOnCancelGamePreparation() }
  }

  val popperViewModel = vocabPopperViewModel
  val runnerViewModel = vocabRunnerViewModel
  val startVocabGame: (XrLanguageTeacherVocabGameRequest) -> Unit = { request ->
    val language = request.language
    when (request.game) {
      XrLanguageTeacherVocabGame.VOCAB_POPPER ->
        if (popperViewModel != null) {
          popperViewModel.startRound(
            learnLanguage = language,
            entries = request.entries,
            mode = request.mode,
          )
          activeGame = StudyActivity.VOCAB_POPPER
        }
      XrLanguageTeacherVocabGame.VOCAB_RUNNER ->
        if (runnerViewModel != null) {
          runnerViewModel.startRun(
            learnLanguage = language,
            entries = request.entries,
            mode = request.mode,
          )
          activeGame = StudyActivity.VOCAB_RUNNER
        }
    }
  }
  val requestVocabGame: (XrLanguageTeacherVocabGameRequest) -> Unit = { request ->
    onPrepareGame?.invoke(request) ?: startVocabGame(request)
  }
  // Starts the game whose stickers got ready, if its start screen is still open.
  LaunchedEffect(readyGame) {
    val request = readyGame ?: return@LaunchedEffect
    onGameStarted()
    if (openStartActivity == request.game.studyActivity) startVocabGame(request)
  }
  val isActiveGameRunning =
    when (activeGame) {
      StudyActivity.VOCAB_POPPER -> popperViewModel?.isSessionActive == true
      StudyActivity.VOCAB_RUNNER -> runnerViewModel?.isSessionActive == true
      else -> false
    }
  if (activeGame != null && !isActiveGameRunning) {
    // The round is gone (for example after the app process was recreated); show its start screen.
    LaunchedEffect(Unit) { activeGame = null }
    return
  }
  if (activeGame == StudyActivity.VOCAB_POPPER && popperViewModel != null) {
    VocabPopperScreen(viewModel = popperViewModel, onExit = closeActivity, modifier = modifier)
    return
  }
  if (activeGame == StudyActivity.VOCAB_RUNNER && runnerViewModel != null) {
    VocabRunnerScreen(viewModel = runnerViewModel, onExit = closeActivity, modifier = modifier)
    return
  }

  val languages = remember(entries) { entries.map { it.displayLearnLanguage() }.distinct() }
  val supportedLanguages =
    remember(languages, isTtsLanguageSupported) { languages.filter { isTtsLanguageSupported(it) } }
  val isNonAsrEnabled = isStudyGamesNonAsrEnabled
  val effectivePopperMode =
    if (isNonAsrEnabled) popperMode else XrLanguageTeacherVocabGameMode.SPEAK
  val effectiveRunnerMode =
    if (isNonAsrEnabled) runnerMode else XrLanguageTeacherVocabGameMode.SPEAK
  val popperOverviewMode = effectivePopperMode
  val popperOverviewMinimum = vocabPopperMinimumCount(popperOverviewMode)
  val popperOverviewEligibleLanguages =
    remember(entries, languages, popperOverviewMinimum) {
      languages.filter { language ->
        entries.count { it.displayLearnLanguage() == language } >= popperOverviewMinimum
      }
    }
  val popperOverviewSupportedLanguages =
    remember(
      popperOverviewEligibleLanguages,
      popperOverviewMode,
      isTtsLanguageSupported,
      isAsrLanguageSupported,
    ) {
      val check =
        if (popperOverviewMode == XrLanguageTeacherVocabGameMode.TAP) {
          isTtsLanguageSupported
        } else {
          isAsrLanguageSupported
        }
      popperOverviewEligibleLanguages.filter { check(it) }
    }
  val runnerOverviewMode = effectiveRunnerMode
  val runnerOverviewMinimum = vocabRunnerMinimumCount(runnerOverviewMode)
  val runnerOverviewEligibleLanguages =
    remember(entries, languages, vocabEntryKeysWithImages, runnerOverviewMinimum) {
      languages.filter { language ->
        entries.count {
          it.displayLearnLanguage() == language &&
            it.vocabGameEntryKey() in vocabEntryKeysWithImages
        } >= runnerOverviewMinimum
      }
    }
  val runnerOverviewSupportedLanguages =
    remember(runnerOverviewEligibleLanguages, runnerOverviewMode, isAsrLanguageSupported) {
      val check: (Language) -> Boolean =
        if (runnerOverviewMode == XrLanguageTeacherVocabGameMode.TAP) {
          { true }
        } else {
          isAsrLanguageSupported
        }
      runnerOverviewEligibleLanguages.filter { check(it) }
    }

  // Closing the review goes straight back to the Study cards, like leaving a game.
  val activeReviewLanguage = reviewLanguage
  if (activeReviewLanguage != null) {
    val deck =
      remember(entries, activeReviewLanguage, flashcardsCardCount, reviewSessionSeed) {
        val shuffled =
          entries
            .filter { it.displayLearnLanguage() == activeReviewLanguage }
            .shuffled(Random(reviewSessionSeed))
        if (flashcardsCardCount > 0) shuffled.take(flashcardsCardCount) else shuffled
      }
    FlashcardReviewScreen(
      entries = deck,
      language = activeReviewLanguage,
      onPlayPhrase = onPlayPhrase,
      loadVocabImageBitmap = loadVocabImageBitmap,
      phrasePlaybackState = phrasePlaybackState,
      onBack = {
        reviewLanguage = null
        openStartActivity = null
      },
      modifier = modifier,
    )
    return
  }

  if (openStartActivity == StudyActivity.FLASHCARDS) {
    BackHandler { openStartActivity = null }
    val language = resolveStudyLanguage(flashcardsLanguage, languages, supportedLanguages)
    val deckSize =
      if (language != null) {
        entries.count { it.displayLearnLanguage() == language }
      } else {
        0
      }
    // Only counts smaller than the deck, then "All" (0), which shows the whole deck.
    val cardCounts = FLASHCARDS_CARD_COUNTS.filter { it < deckSize } + 0
    val cardCount = flashcardsCardCount.takeIf { it in cardCounts } ?: 0
    VocabGameStartScreen(
      title = stringResource(R.string.xrteacher_flashcards_title),
      subtitle = stringResource(R.string.xrteacher_flashcards_subtitle),
      steps =
        listOf(
          stringResource(R.string.xrteacher_flashcards_step_1),
          stringResource(R.string.xrteacher_flashcards_step_2),
          stringResource(R.string.xrteacher_flashcards_step_3),
        ),
      isPlayEnabled = deckSize > 0 && language != null && isTtsLanguageSupported(language),
      // Flash cards can't start in a language the voice model can't speak.
      lockedMessage =
        if (language != null && !isTtsLanguageSupported(language)) {
          stringResource(R.string.xrteacher_flashcards_voice_unsupported, getLanguageName(language))
        } else {
          null
        },
      lockedActionLabel =
        onOpenAudioSettings?.let {
          stringResource(R.string.xrteacher_tts_unsupported_open_settings)
        },
      onLockedAction = onOpenAudioSettings,
      changeModelLabel =
        onOpenAudioSettings?.let { stringResource(R.string.xrteacher_change_tts_model) },
      onChangeModel = onOpenAudioSettings,
      onBack = { openStartActivity = null },
      onPlay = {
        flashcardsCardCount = cardCount
        reviewSessionSeed++
        reviewLanguage = language
      },
      modifier = modifier,
      playLabel = stringResource(R.string.xrteacher_flashcards_start_review),
      playIcon = Icons.Filled.School,
      languageSetting = {
        VocabGameLanguageDropdown(
          languages = languages,
          selected = language,
          onSelect = { flashcardsLanguage = it },
          isLanguageEnabled = isTtsLanguageSupported,
          languageSupportingText = { option ->
            if (isTtsLanguageSupported(option)) null
            else stringResource(R.string.xrteacher_language_not_supported_by_tts)
          },
        )
      },
    ) {
      VocabGameDropdown(
        label = stringResource(R.string.xrteacher_flashcards_setting_count),
        options = cardCounts,
        selected = cardCount,
        optionLabel = { count ->
          if (count > 0) {
            pluralStringResource(R.plurals.xrteacher_flashcards_card_count, count, count)
          } else {
            stringResource(R.string.xrteacher_flashcards_card_count_all, deckSize)
          }
        },
        placeholder = "",
        onSelect = { flashcardsCardCount = it },
      )
    }
    return
  }

  if (openStartActivity == StudyActivity.VOCAB_POPPER) {
    BackHandler(onBack = closeActivity)
    val minCount = vocabPopperMinimumCount(effectivePopperMode)
    val eligibleLanguages =
      remember(entries, languages, minCount) {
        languages.filter { language ->
          entries.count { it.displayLearnLanguage() == language } >= minCount
        }
      }
    val languageSupportCheck: (Language) -> Boolean =
      if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
        isTtsLanguageSupported
      } else {
        isAsrLanguageSupported
      }
    val supportedEligibleLanguages =
      remember(
        eligibleLanguages,
        effectivePopperMode,
        isTtsLanguageSupported,
        isAsrLanguageSupported,
      ) {
        eligibleLanguages.filter { languageSupportCheck(it) }
      }
    val language =
      resolveStudyLanguage(popperLanguage, eligibleLanguages, supportedEligibleLanguages)
    val deck =
      if (language != null) entries.filter { it.displayLearnLanguage() == language }
      else emptyList()
    val hasEnoughWords = deck.size >= minCount
    val isLanguageSupported = language != null && languageSupportCheck(language)
    DisposableEffect(language, effectivePopperMode, deck, hasEnoughWords, isLanguageSupported) {
      if (language != null && hasEnoughWords && isLanguageSupported) {
        onPrewarmGame?.invoke(
          XrLanguageTeacherVocabGameRequest(
            game = XrLanguageTeacherVocabGame.VOCAB_POPPER,
            language = language,
            entries = deck,
            mode = effectivePopperMode,
          )
        )
      } else {
        latestOnCancelGamePrewarm()
      }
      onDispose {
        if (activity?.isChangingConfigurations != true && latestActiveGame == null) {
          latestOnCancelGamePrewarm()
        }
      }
    }
    val modelSettingsAction =
      if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
        onOpenAudioSettings
      } else {
        onOpenAsrSettings
      }
    val lockedMessage =
      when {
        !hasEnoughWords ->
          pluralStringResource(R.plurals.xrteacher_vocab_popper_locked, minCount, minCount)
        language != null && !isLanguageSupported ->
          if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
            stringResource(R.string.xrteacher_study_game_tts_unsupported_language_warning)
          } else {
            stringResource(R.string.xrteacher_game_asr_unsupported, getLanguageName(language))
          }
        else -> null
      }
    val lockedActionLabel =
      if (
        hasEnoughWords && language != null && !isLanguageSupported && modelSettingsAction != null
      ) {
        stringResource(
          if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
            R.string.xrteacher_tts_unsupported_open_settings
          } else {
            R.string.xrteacher_asr_unsupported_open_settings
          }
        )
      } else {
        null
      }
    VocabGameStartScreen(
      title = stringResource(R.string.xrteacher_vocab_popper_title),
      subtitle =
        stringResource(
          if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
            R.string.xrteacher_vocab_popper_tap_subtitle
          } else {
            R.string.xrteacher_vocab_popper_subtitle
          }
        ),
      steps =
        if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
          listOf(
            stringResource(R.string.xrteacher_vocab_popper_tap_step_1),
            stringResource(R.string.xrteacher_vocab_popper_tap_step_2),
            stringResource(R.string.xrteacher_vocab_popper_tap_step_3),
          )
        } else {
          listOf(
            stringResource(R.string.xrteacher_vocab_popper_step_1),
            stringResource(R.string.xrteacher_vocab_popper_step_2),
            stringResource(R.string.xrteacher_vocab_popper_step_3),
          )
        },
      isPlayEnabled = hasEnoughWords && isLanguageSupported && popperViewModel != null,
      lockedMessage = lockedMessage,
      lockedActionLabel = lockedActionLabel,
      onLockedAction = if (lockedActionLabel != null) modelSettingsAction else null,
      changeModelLabel =
        modelSettingsAction?.let {
          stringResource(
            if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
              R.string.xrteacher_change_tts_model
            } else {
              R.string.xrteacher_change_asr_model
            }
          )
        },
      onChangeModel = modelSettingsAction,
      onBack = closeActivity,
      onPlay = {
        if (language != null && popperViewModel != null) {
          requestVocabGame(
            XrLanguageTeacherVocabGameRequest(
              game = XrLanguageTeacherVocabGame.VOCAB_POPPER,
              language = language,
              entries = deck,
              mode = effectivePopperMode,
            )
          )
        }
      },
      languageSetting = {
        VocabGameLanguageDropdown(
          languages = eligibleLanguages,
          selected = language,
          onSelect = {
            onCancelGamePreparation()
            popperLanguage = it
          },
          isLanguageEnabled = languageSupportCheck,
          languageSupportingText = { option ->
            if (languageSupportCheck(option)) {
              null
            } else {
              stringResource(
                if (effectivePopperMode == XrLanguageTeacherVocabGameMode.TAP) {
                  R.string.xrteacher_language_not_supported_by_tts
                } else {
                  R.string.xrteacher_language_not_supported_by_asr
                }
              )
            }
          },
        )
      },
      moreSettings =
        if (isNonAsrEnabled) {
          {
            VocabGameDropdown(
              label = stringResource(R.string.xrteacher_game_setting_mode),
              options = XrLanguageTeacherVocabGameMode.entries,
              selected = effectivePopperMode,
              optionLabel = { mode ->
                stringResource(
                  when (mode) {
                    XrLanguageTeacherVocabGameMode.TAP -> R.string.xrteacher_game_mode_tap
                    XrLanguageTeacherVocabGameMode.SPEAK -> R.string.xrteacher_game_mode_speak
                  }
                )
              },
              placeholder = "",
              onSelect = {
                onCancelGamePreparation()
                popperMode = it
              },
            )
          }
        } else {
          null
        },
      isPreparing = isPreparingGame,
      modifier = modifier,
    )
    return
  }

  if (openStartActivity == StudyActivity.VOCAB_RUNNER) {
    BackHandler(onBack = closeActivity)
    val minCount = vocabRunnerMinimumCount(effectiveRunnerMode)
    val eligibleLanguages =
      remember(entries, languages, vocabEntryKeysWithImages, minCount) {
        languages.filter { language ->
          entries.count {
            it.displayLearnLanguage() == language &&
              it.vocabGameEntryKey() in vocabEntryKeysWithImages
          } >= minCount
        }
      }
    val languageSupportCheck: (Language) -> Boolean =
      if (effectiveRunnerMode == XrLanguageTeacherVocabGameMode.TAP) {
        { true }
      } else {
        isAsrLanguageSupported
      }
    val supportedEligibleLanguages =
      remember(eligibleLanguages, effectiveRunnerMode, isAsrLanguageSupported) {
        eligibleLanguages.filter { languageSupportCheck(it) }
      }
    val language =
      resolveStudyLanguage(runnerLanguage, eligibleLanguages, supportedEligibleLanguages)
    val deck =
      if (language != null) {
        entries.filter {
          it.displayLearnLanguage() == language &&
            it.vocabGameEntryKey() in vocabEntryKeysWithImages
        }
      } else {
        emptyList()
      }
    val hasEnoughWords = deck.size >= minCount
    val isLanguageSupported = language != null && languageSupportCheck(language)
    DisposableEffect(language, effectiveRunnerMode, deck, hasEnoughWords, isLanguageSupported) {
      if (language != null && hasEnoughWords && isLanguageSupported) {
        onPrewarmGame?.invoke(
          XrLanguageTeacherVocabGameRequest(
            game = XrLanguageTeacherVocabGame.VOCAB_RUNNER,
            language = language,
            entries = deck,
            mode = effectiveRunnerMode,
          )
        )
      } else {
        latestOnCancelGamePrewarm()
      }
      onDispose {
        if (activity?.isChangingConfigurations != true && latestActiveGame == null) {
          latestOnCancelGamePrewarm()
        }
      }
    }
    val modelSettingsAction =
      if (effectiveRunnerMode == XrLanguageTeacherVocabGameMode.TAP) null else onOpenAsrSettings
    val lockedMessage =
      when {
        !hasEnoughWords ->
          pluralStringResource(R.plurals.xrteacher_vocab_runner_locked, minCount, minCount)
        language != null && !isLanguageSupported ->
          stringResource(R.string.xrteacher_game_asr_unsupported, getLanguageName(language))
        else -> null
      }
    val lockedActionLabel =
      if (
        hasEnoughWords && language != null && !isLanguageSupported && modelSettingsAction != null
      ) {
        stringResource(R.string.xrteacher_asr_unsupported_open_settings)
      } else {
        null
      }
    val runnerSeconds =
      (XrLanguageTeacherVocabRunnerEngine.ROUND_DURATION_MILLISECONDS / 1_000L).toInt()
    VocabGameStartScreen(
      title = stringResource(R.string.xrteacher_vocab_runner_title),
      subtitle =
        if (effectiveRunnerMode == XrLanguageTeacherVocabGameMode.TAP) {
          stringResource(R.string.xrteacher_vocab_runner_tap_subtitle)
        } else {
          pluralStringResource(
            R.plurals.xrteacher_vocab_runner_subtitle,
            runnerSeconds,
            runnerSeconds,
          )
        },
      steps =
        if (effectiveRunnerMode == XrLanguageTeacherVocabGameMode.TAP) {
          listOf(
            stringResource(R.string.xrteacher_vocab_runner_tap_step_1),
            stringResource(R.string.xrteacher_vocab_runner_tap_step_2),
            stringResource(R.string.xrteacher_vocab_runner_tap_step_3),
          )
        } else {
          listOf(
            stringResource(R.string.xrteacher_vocab_runner_step_1),
            stringResource(R.string.xrteacher_vocab_runner_step_2),
            pluralStringResource(
              R.plurals.xrteacher_vocab_runner_step_3,
              runnerSeconds,
              runnerSeconds,
            ),
          )
        },
      isPlayEnabled = hasEnoughWords && isLanguageSupported && runnerViewModel != null,
      lockedMessage = lockedMessage,
      lockedActionLabel = lockedActionLabel,
      onLockedAction = if (lockedActionLabel != null) modelSettingsAction else null,
      changeModelLabel =
        modelSettingsAction?.let { stringResource(R.string.xrteacher_change_asr_model) },
      onChangeModel = modelSettingsAction,
      onBack = closeActivity,
      onPlay = {
        if (language != null && runnerViewModel != null) {
          requestVocabGame(
            XrLanguageTeacherVocabGameRequest(
              game = XrLanguageTeacherVocabGame.VOCAB_RUNNER,
              language = language,
              entries = deck,
              mode = effectiveRunnerMode,
            )
          )
        }
      },
      playIcon = Icons.AutoMirrored.Filled.DirectionsRun,
      isPreparing = isPreparingGame,
      modifier = modifier,
      languageSetting = {
        VocabGameLanguageDropdown(
          languages = eligibleLanguages,
          selected = language,
          onSelect = {
            onCancelGamePreparation()
            runnerLanguage = it
          },
          isLanguageEnabled = languageSupportCheck,
          languageSupportingText = { option ->
            if (languageSupportCheck(option)) null
            else stringResource(R.string.xrteacher_language_not_supported_by_asr)
          },
        )
      },
      moreSettings =
        if (isNonAsrEnabled) {
          {
            VocabGameDropdown(
              label = stringResource(R.string.xrteacher_game_setting_mode),
              options = XrLanguageTeacherVocabGameMode.entries,
              selected = effectiveRunnerMode,
              optionLabel = { mode ->
                stringResource(
                  when (mode) {
                    XrLanguageTeacherVocabGameMode.TAP -> R.string.xrteacher_game_mode_tap
                    XrLanguageTeacherVocabGameMode.SPEAK -> R.string.xrteacher_game_mode_speak
                  }
                )
              },
              placeholder = "",
              onSelect = {
                onCancelGamePreparation()
                runnerMode = it
              },
            )
          }
        } else {
          null
        },
    )
    return
  }

  Box(modifier = modifier.fillMaxSize().clipToBounds()) {
    StudyBackgroundShapes()
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.xrteacher_study_header),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.xrteacher_study_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (onConvertToStickers != null) {
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onConvertToStickers) {
          Text(
            text = stringResource(R.string.xrteacher_study_convert_images_to_stickers),
            style = MaterialTheme.typography.labelLarge,
          )
        }
        Spacer(modifier = Modifier.height(12.dp))
      } else {
        Spacer(modifier = Modifier.height(24.dp))
      }
      val noSupportedLanguagesWarning =
        if (languages.isNotEmpty() && supportedLanguages.isEmpty()) {
          stringResource(R.string.xrteacher_study_tts_unsupported_warning)
        } else {
          null
        }
      FlashcardsStudyCard(
        isOpenEnabled = supportedLanguages.isNotEmpty(),
        onOpen = { openStartActivity = StudyActivity.FLASHCARDS },
        lockedMessage = noSupportedLanguagesWarning,
        onOpenAudioSettings = onOpenAudioSettings,
      )
      Spacer(modifier = Modifier.height(16.dp))
      val allPopperEligibleUnsupported =
        popperOverviewEligibleLanguages.isNotEmpty() && popperOverviewSupportedLanguages.isEmpty()
      val popperWarningAction =
        if (popperOverviewMode == XrLanguageTeacherVocabGameMode.TAP) {
          onOpenAudioSettings
        } else {
          onOpenAsrSettings
        }
      val popperLockedMessage =
        when {
          popperOverviewEligibleLanguages.isEmpty() ->
            pluralStringResource(
              R.plurals.xrteacher_vocab_popper_locked,
              popperOverviewMinimum,
              popperOverviewMinimum,
            )
          allPopperEligibleUnsupported ->
            stringResource(
              if (popperOverviewMode == XrLanguageTeacherVocabGameMode.TAP) {
                R.string.xrteacher_study_game_tts_unsupported_warning
              } else {
                R.string.xrteacher_study_asr_unsupported_warning
              }
            )
          else -> null
        }
      VocabPopperStudyCard(
        isOpenEnabled = popperOverviewSupportedLanguages.isNotEmpty(),
        onOpen = { openStartActivity = StudyActivity.VOCAB_POPPER },
        lockedMessage = popperLockedMessage,
        lockedActionLabel =
          if (allPopperEligibleUnsupported && popperWarningAction != null) {
            stringResource(
              if (popperOverviewMode == XrLanguageTeacherVocabGameMode.TAP) {
                R.string.xrteacher_tts_unsupported_open_settings
              } else {
                R.string.xrteacher_asr_unsupported_open_settings
              }
            )
          } else {
            null
          },
        onLockedAction = if (allPopperEligibleUnsupported) popperWarningAction else null,
      )
      Spacer(modifier = Modifier.height(16.dp))
      val allRunnerEligibleUnsupported =
        runnerOverviewEligibleLanguages.isNotEmpty() && runnerOverviewSupportedLanguages.isEmpty()
      val runnerLockedMessage =
        when {
          areVocabImagesChecked && runnerOverviewEligibleLanguages.isEmpty() ->
            pluralStringResource(
              R.plurals.xrteacher_vocab_runner_locked,
              runnerOverviewMinimum,
              runnerOverviewMinimum,
            )
          areVocabImagesChecked && allRunnerEligibleUnsupported ->
            stringResource(R.string.xrteacher_study_asr_unsupported_warning)
          else -> null
        }
      VocabRunnerStudyCard(
        isOpenEnabled = runnerOverviewSupportedLanguages.isNotEmpty(),
        onOpen = { openStartActivity = StudyActivity.VOCAB_RUNNER },
        lockedMessage = runnerLockedMessage,
        lockedActionLabel =
          if (areVocabImagesChecked && allRunnerEligibleUnsupported && onOpenAsrSettings != null) {
            stringResource(R.string.xrteacher_asr_unsupported_open_settings)
          } else {
            null
          },
        onLockedAction =
          if (areVocabImagesChecked && allRunnerEligibleUnsupported) onOpenAsrSettings else null,
      )
      if (languages.isEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = stringResource(R.string.xrteacher_no_vocabulary),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/**
 * The language a Study card uses: [selected] if it still has saved words and is supported by the
 * active TTS engine, otherwise the first TTS-supported language in [supported], otherwise the first
 * language in [available].
 */
private fun resolveStudyLanguage(
  selected: Language?,
  available: List<Language>,
  supported: List<Language>,
): Language? =
  selected?.takeIf { it in available && it in supported }
    ?: supported.firstOrNull()
    ?: available.firstOrNull()

/** The Study activity that plays this game. */
private val XrLanguageTeacherVocabGame.studyActivity: StudyActivity
  get() =
    when (this) {
      XrLanguageTeacherVocabGame.VOCAB_POPPER -> StudyActivity.VOCAB_POPPER
      XrLanguageTeacherVocabGame.VOCAB_RUNNER -> StudyActivity.VOCAB_RUNNER
    }
