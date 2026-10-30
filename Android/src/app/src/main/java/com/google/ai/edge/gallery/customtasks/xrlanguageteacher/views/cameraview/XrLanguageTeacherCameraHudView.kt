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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.cameraview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngine
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SpeechEngineReadiness
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherInitStep
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherInitStepStatus
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherDialogueFormatter
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.displayNameResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toNameResId
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.AnnotatedTargetClickableText
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.RotationalLoader
import com.google.ai.edge.gallery.ui.common.asr.AsrRecordingPill
import com.google.ai.edge.gallery.ui.common.asr.AsrRecordingPillColors
import com.google.ai.edge.gallery.ui.common.asr.asrRecordingPillColors
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.inversePrimaryLight
import com.google.ai.edge.gallery.ui.theme.onPrimaryDark
import kotlinx.coroutines.delay

/**
 * Mockup (a): Top dark gradient scrim with centered guidance text shown while the user is recording
 * their initial question about an object in the camera view.
 */
@Composable
fun InitialAsrTopGuidanceScrim(modifier: Modifier = Modifier) {
  val scrimColor = MaterialTheme.colorScheme.inverseSurface
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(156.dp)
        .background(
          brush =
            Brush.verticalGradient(
              colors =
                listOf(
                  scrimColor.copy(alpha = 0.82f),
                  scrimColor.copy(alpha = 0.48f),
                  Color.Transparent,
                )
            )
        )
        .padding(horizontal = 32.dp, vertical = 24.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Text(
      text = stringResource(R.string.xrteacher_center_object_guidance),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
      color = MaterialTheme.colorScheme.inverseOnSurface,
      textAlign = TextAlign.Center,
    )
  }
}

/** Pre-canned status phrases that rotate under the "Thinking…" header, in display order. */
internal val THINKING_PHRASE_RES_IDS: List<Int> =
  listOf(
    R.string.xrteacher_thinking_phrase_decoding_pixels,
    R.string.xrteacher_thinking_phrase_identifying_objects,
    R.string.xrteacher_thinking_phrase_looking_closer,
    R.string.xrteacher_thinking_phrase_finding_words,
    R.string.xrteacher_thinking_phrase_picking_vocab,
    R.string.xrteacher_thinking_phrase_preparing_lesson,
  )

/** How long each thinking phrase stays on screen before the next one slides in. */
private const val THINKING_PHRASE_INTERVAL_MS = 2_200L

/** Size of the Gallery loading animation next to the "Thinking…" header. */
private val THINKING_LOADER_SIZE = 20.dp

/** Returns the index of the phrase after [current], wrapping around a list of [count] phrases. */
internal fun nextThinkingPhraseIndex(current: Int, count: Int): Int =
  if (count <= 0) 0 else (current + 1) % count

/**
 * Mockups (b) & (e): Soft atmospheric top gradient glow shown at the top of the frozen camera frame
 * while waiting for the first `<reply>` token. Shows the Gallery [RotationalLoader] next to a
 * centered `"Thinking…"` header, with pre-canned status phrases from [phraseResIds] rotating
 * underneath.
 */
@Composable
fun TopThinkingGlowOverlay(
  modifier: Modifier = Modifier,
  phraseResIds: List<Int> = THINKING_PHRASE_RES_IDS,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "ThinkingGlowTransition")
  val glowAlpha by
    infiniteTransition.animateFloat(
      initialValue = 0.82f,
      targetValue = 1.0f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1100, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "ThinkingGlowAlpha",
    )

  // The glow sits on camera pixels, so it uses the theme-independent camera HUD colors (the deep
  // blue with white text from the design) instead of tokens that flip in dark theme.
  val glowColor = MaterialTheme.customColors.cameraHudGlowColor
  val onGlowColor = MaterialTheme.customColors.onCameraHudGlowColor
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(168.dp)
        .background(
          brush =
            Brush.verticalGradient(
              colors =
                listOf(
                  glowColor.copy(alpha = 0.92f * glowAlpha),
                  glowColor.copy(alpha = 0.55f * glowAlpha),
                  Color.Transparent,
                )
            )
        )
        .padding(horizontal = 24.dp, vertical = 28.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RotationalLoader(size = THINKING_LOADER_SIZE)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.xrteacher_thinking_header),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
          color = onGlowColor,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      RotatingThinkingPhrase(phraseResIds = phraseResIds, color = onGlowColor)
    }
  }
}

/**
 * Cycles through [phraseResIds], sliding each phrase in from the end edge and out toward the start
 * edge while fading. The timer lives in this composable, so it stops when the overlay leaves the
 * composition and restarts from the first phrase on the next thinking turn.
 *
 * The phrases are decorative, so they are hidden from accessibility services to avoid announcing a
 * new phrase every few seconds; the "Thinking…" header conveys the state.
 */
@Composable
private fun RotatingThinkingPhrase(phraseResIds: List<Int>, color: Color) {
  if (phraseResIds.isEmpty()) return
  var phraseIndex by remember(phraseResIds) { mutableIntStateOf(0) }
  LaunchedEffect(phraseResIds) {
    while (true) {
      delay(THINKING_PHRASE_INTERVAL_MS)
      phraseIndex = nextThinkingPhraseIndex(phraseIndex, phraseResIds.size)
    }
  }
  // Slide offsets are absolute, so flip them in RTL to keep entering from the end edge.
  val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
  AnimatedContent(
    targetState = phraseIndex,
    transitionSpec = {
      (slideInHorizontally(tween(durationMillis = 320)) { width -> direction * width / 4 } +
        fadeIn(tween(durationMillis = 320))) togetherWith
        (slideOutHorizontally(tween(durationMillis = 240)) { width -> -direction * width / 4 } +
          fadeOut(tween(durationMillis = 200)))
    },
    contentAlignment = Alignment.Center,
    label = "ThinkingPhrase",
    modifier = Modifier.clearAndSetSemantics {},
  ) { index ->
    Text(
      text = stringResource(phraseResIds[index]),
      style = MaterialTheme.typography.bodyMedium,
      color = color,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Mockup (b): Bottom dark scrim with the user's transcribed question and centered Stop button while
 * waiting for the first `<reply>` token on Turn 1.
 */
@Composable
fun InitialThinkingBottomQuestionOverlay(
  questionText: String,
  modifier: Modifier = Modifier,
  onStopThinking: () -> Unit = {},
) {
  val scrimColor = MaterialTheme.colorScheme.inverseSurface
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .background(
          brush =
            Brush.verticalGradient(
              colors =
                listOf(
                  Color.Transparent,
                  scrimColor.copy(alpha = 0.55f),
                  scrimColor.copy(alpha = 0.85f),
                )
            )
        )
        .padding(horizontal = 16.dp, vertical = 16.dp),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (questionText.isNotBlank()) {
        Text(
          text = questionText,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.inverseOnSurface,
          textAlign = TextAlign.Center,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
      }
      Surface(
        onClick = onStopThinking,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(48.dp),
      ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.cd_stop_icon),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
  }
}

/**
 * Mockups (c) & (f): Top Teacher HUD Bubble — translucent inverse surface rounded card with
 * overlapping persona and localized language-pair pill badges on its top-left border, live TTS/ASR
 * engine status indicator dots, `inverseOnSurface` dialogue text with `<target>` words/sentences
 * highlighted in bold `inversePrimary`, and a `secondaryContainer` (`40.dp`) speaker/mute toggle
 * button on the top-right.
 */
@Composable
fun TopTeacherHudBubble(
  dialogueText: String,
  persona: CharacterPersona,
  isSpeakingTts: Boolean,
  onToggleTts: () -> Unit,
  onPlayTargetPhrase: (String) -> Unit,
  mainLanguage: Language,
  learnLanguage: Language,
  modifier: Modifier = Modifier,
  ttsEngine: SpeechEngine = SpeechEngine.MLKIT_TTS,
  ttsReadiness: SpeechEngineReadiness = SpeechEngineReadiness.WARMING,
  asrEngine: SpeechEngine = SpeechEngine.MLKIT_ASR,
  asrReadiness: SpeechEngineReadiness = SpeechEngineReadiness.WARMING,
) {
  if (dialogueText.isBlank()) return

  val scrollState = rememberScrollState()
  LaunchedEffect(dialogueText) { scrollState.animateScrollTo(scrollState.maxValue) }

  Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    // Dark translucent rounded card body pushed down slightly so the top-left badges overlap its
    // border
    Surface(
      modifier = Modifier.fillMaxWidth().padding(top = 13.dp),
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.14f)),
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
      ) {
        val annotatedDialogue =
          XrLanguageTeacherDialogueFormatter.formatAnnotatedString(
            dialogueText,
            targetColor = MaterialTheme.colorScheme.inversePrimary,
            pillBackgroundColor = Color.Transparent,
          )

        Column(modifier = Modifier.weight(1f)) {
          Row(
            modifier = Modifier.padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SpeechEngineStatusIndicator(
              label = speechEngineLabel(ttsEngine, ttsReadiness),
              readiness = ttsReadiness,
              contentDescriptionRes = R.string.xrteacher_tts_engine_status,
            )
            SpeechEngineStatusIndicator(
              label = speechEngineLabel(asrEngine, asrReadiness),
              readiness = asrReadiness,
              contentDescriptionRes = R.string.xrteacher_asr_engine_status,
            )
          }

          Box(
            modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp).verticalScroll(scrollState)
          ) {
            AnnotatedTargetClickableText(
              text = annotatedDialogue,
              style =
                MaterialTheme.typography.bodyLarge.copy(
                  color = MaterialTheme.colorScheme.inverseOnSurface,
                  fontWeight = FontWeight.Medium,
                ),
              onTargetPhraseClick = onPlayTargetPhrase,
            )
          }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Surface(
          onClick = onToggleTts,
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
          modifier = Modifier.size(40.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                if (isSpeakingTts) {
                  Icons.Filled.Stop
                } else {
                  Icons.Filled.PlayArrow
                },
              contentDescription =
                if (isSpeakingTts) {
                  stringResource(R.string.xrteacher_stop_audio)
                } else {
                  stringResource(R.string.xrteacher_listen)
                },
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }

    // Overlapping pill badges at top-left (persona chip + language pair chip)
    val mainLanguageName = stringResource(mainLanguage.toNameResId())
    val learnLanguageName = stringResource(learnLanguage.toNameResId())
    val languagePairDescription =
      stringResource(R.string.xrteacher_hud_language_pair_desc, mainLanguageName, learnLanguageName)
    Row(
      modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = 56.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
      ) {
        Text(
          text = stringResource(persona.displayNameResId()),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
      }
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier =
          Modifier.weight(1f, fill = false).clearAndSetSemantics {
            contentDescription = languagePairDescription
          },
      ) {
        Text(
          text =
            stringResource(
              R.string.xrteacher_hud_language_pair,
              mainLanguageName,
              learnLanguageName,
            ),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }
    }
  }
}

/**
 * Full-surface loading indicator matching AI Chat and AR Translator while the teacher model, the
 * speech recognition engine and the voice start. Each of [steps] is a line that ticks once its part
 * is ready. There is no timeout; background conversation session pre-warming runs non-blockingly
 * behind the ready viewfinder.
 */
@Composable
fun CameraInitializingOverlay(steps: List<TeacherInitStep>, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      RotationalLoader(size = 32.dp)
      Text(
        text = stringResource(R.string.xrteacher_initializing_model),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Column(
        modifier = Modifier.padding(top = 8.dp).width(IntrinsicSize.Max),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        for (step in steps) {
          TeacherInitStepRow(step = step)
        }
      }
    }
  }
}

/** A line of [CameraInitializingOverlay]: a status icon and the part's label. */
@Composable
private fun TeacherInitStepRow(step: TeacherInitStep) {
  val label = stringResource(step.kind.labelResId)
  val description =
    stringResource(
      when (step.status) {
        TeacherInitStepStatus.IN_PROGRESS -> R.string.xrteacher_init_step_in_progress
        TeacherInitStepStatus.READY -> R.string.xrteacher_init_step_ready
        TeacherInitStepStatus.FAILED -> R.string.xrteacher_init_step_failed
      },
      label,
    )
  Row(
    modifier =
      Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = description
        liveRegion = LiveRegionMode.Polite
      },
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when (step.status) {
      TeacherInitStepStatus.IN_PROGRESS ->
        CircularProgressIndicator(
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 2.dp,
          modifier = Modifier.size(20.dp).padding(2.dp),
        )
      TeacherInitStepStatus.READY ->
        Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      TeacherInitStepStatus.FAILED ->
        Icon(
          imageVector = Icons.Filled.Error,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(20.dp),
        )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color =
        if (step.status == TeacherInitStepStatus.IN_PROGRESS) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSurface
        },
    )
  }
}

/**
 * Camera HUD bottom input row, a 1-tap action row where every option sends the current camera
 * frame:
 * - **Idle voice-first state (`!usesText`)**: a [CameraHudIconButton] camera button on the start
 *   side that snaps a photo for a vocabulary card without speaking ([onTakePhoto]), a wide GM3
 *   `"Ask a question"` mic [Button] in the middle, and a keyboard button on the end side to switch
 *   to typing, with one soft blue glow behind the whole row ([softPillGlow]).
 * - **Idle text state (`usesText`)**: the camera button stays put, a [CameraTextPromptBox] replaces
 *   the mic button, and a mic button replaces the keyboard button to switch back to speaking.
 * - **ASR recording state ([isRecordingAsr])**: live transcript + shared [AsrRecordingPill].
 *
 * @param appearDisabled When true while [enabled] is true, renders the whole row in its faded
 *   disabled colors while keeping it clickable, so tapping it can prompt the user to enable camera
 *   access in system Settings.
 * @param micAppearDisabled Like [appearDisabled], for the mic buttons only (for example when only
 *   microphone access is off).
 * @param onDisabledClick Called when the user taps the text box while it [appearDisabled].
 * @param inputRowModifier Modifier for the row of the three buttons, for example to find its bounds
 *   for the tooltip tour.
 */
@Composable
fun CameraBottomAsrControls(
  isRecordingAsr: Boolean,
  liveTranscript: String,
  confirmedTranscript: String,
  audioLevel: Float,
  enabled: Boolean,
  onStartRecording: () -> Unit,
  onCancelRecording: () -> Unit,
  onSendRecording: () -> Unit,
  modifier: Modifier = Modifier,
  appearDisabled: Boolean = false,
  micAppearDisabled: Boolean = appearDisabled,
  usesText: Boolean = false,
  onUsesTextChange: (Boolean) -> Unit = {},
  onSendText: (String) -> Unit = {},
  onTakePhoto: () -> Unit = {},
  onDisabledClick: () -> Unit = {},
  inputRowModifier: Modifier = Modifier,
) {
  // These controls sit on camera pixels rather than a themed surface, so they use colors that are
  // the same in light and dark themes: a light blue mic button and glow from Color.kt, dark tonal
  // side buttons, and white transcript text over the black scrim. Theme tokens such as
  // inverseOnSurface flip to dark text in dark theme, which is unreadable over the camera feed.
  val scrimColor = MaterialTheme.colorScheme.scrim
  val onScrimColor = MaterialTheme.customColors.onCameraHudGlowColor
  // Opens the keyboard only when the user just tapped the keyboard button, not on recomposition.
  var focusTextOnShow by remember { mutableStateOf(false) }
  Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
    // Atmospheric bottom glow when recording in Mockup (a)
    AnimatedVisibility(
      visible = isRecordingAsr,
      enter = fadeIn(animationSpec = tween(220)),
      exit = fadeOut(animationSpec = tween(180)),
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(210.dp),
    ) {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(
              brush =
                Brush.verticalGradient(
                  colors =
                    listOf(
                      Color.Transparent,
                      scrimColor.copy(alpha = 0.55f),
                      scrimColor.copy(alpha = 0.88f),
                    )
                )
            )
      )
    }

    AnimatedContent(
      targetState = isRecordingAsr,
      transitionSpec = {
        // Not clipped, so the mic button's glow can spread past the row.
        (fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(160))).using(
          SizeTransform(clip = false)
        )
      },
      label = "CameraBottomAsrControlsTransition",
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
    ) { recording ->
      if (!recording) {
        val showRowGlow = !usesText && enabled && !micAppearDisabled
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(top = 8.dp, bottom = 16.dp)
              .then(inputRowModifier)
              // One soft glow behind all three buttons, drawn before them so it never covers them.
              .then(if (showRowGlow) Modifier.softPillGlow(inversePrimaryLight) else Modifier),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CameraHudIconButton(
            icon = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(R.string.xrteacher_take_photo_desc),
            enabled = enabled,
            appearDisabled = appearDisabled,
            onClick = onTakePhoto,
          )
          if (usesText) {
            CameraTextPromptBox(
              enabled = enabled,
              appearDisabled = appearDisabled,
              onDisabledClick = onDisabledClick,
              onSend = { question ->
                focusTextOnShow = false
                onSendText(question)
              },
              focusOnShow = focusTextOnShow,
              onFocusConsumed = { focusTextOnShow = false },
              modifier = Modifier.weight(1f),
            )
            CameraHudIconButton(
              icon = Icons.Default.Mic,
              contentDescription = stringResource(R.string.xrteacher_speak_question_desc),
              enabled = enabled,
              appearDisabled = micAppearDisabled,
              onClick = {
                focusTextOnShow = false
                onUsesTextChange(false)
              },
            )
          } else {
            CameraHudAskButton(
              enabled = enabled,
              appearDisabled = micAppearDisabled,
              onClick = onStartRecording,
              modifier = Modifier.weight(1f),
            )
            CameraHudIconButton(
              icon = Icons.Default.Keyboard,
              contentDescription = stringResource(R.string.xrteacher_type_question_desc),
              enabled = enabled,
              appearDisabled = appearDisabled,
              onClick = {
                focusTextOnShow = true
                onUsesTextChange(true)
              },
            )
          }
        }
      } else {
        val displayTranscript = liveTranscript.ifBlank { confirmedTranscript }.trim()
        Column(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text =
              displayTranscript.ifBlank { stringResource(R.string.xrteacher_asr_listening_hint) },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color =
              if (displayTranscript.isNotBlank()) onScrimColor
              else onScrimColor.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
          )

          AsrRecordingPill(
            audioLevel = audioLevel,
            cancelContentDescription = stringResource(R.string.xrteacher_asr_cancel_desc),
            sendContentDescription = stringResource(R.string.xrteacher_asr_send_desc),
            onCancel = onCancelRecording,
            onSend = onSendRecording,
            colors = teacherAsrRecordingPillColors(),
          )
        }
      }
    }
  }
}

/**
 * The wide `"Ask a question"` mic button of the idle camera HUD row: a GM3 filled [Button] styled
 * like the camera's original round mic button, with a light blue fill and a thin, 50% white ring.
 * The soft blue glow is drawn behind the whole row ([CameraBottomAsrControls]), not by the button.
 *
 * It sits on camera pixels rather than a themed surface, so it uses colors that are the same in
 * light and dark themes.
 *
 * @param appearDisabled When true while [enabled] is true, renders the button in its greyed-out
 *   disabled palette without glow, while keeping it clickable so tapping it can prompt the user to
 *   enable permission in system Settings.
 */
@Composable
internal fun CameraHudAskButton(
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  appearDisabled: Boolean = false,
) {
  val visuallyEnabled = enabled && !appearDisabled
  val onScrimColor = MaterialTheme.customColors.onCameraHudGlowColor
  val fadedContainerColor = inversePrimaryLight.copy(alpha = 0.42f)
  val fadedContentColor = onPrimaryDark.copy(alpha = 0.5f)
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = if (appearDisabled) fadedContainerColor else inversePrimaryLight,
        contentColor = if (appearDisabled) fadedContentColor else onPrimaryDark,
        disabledContainerColor = fadedContainerColor,
        disabledContentColor = fadedContentColor,
      ),
    // A soft, semi-transparent white ring, so the button reads over any camera background.
    border =
      BorderStroke(
        1.dp,
        onScrimColor.copy(alpha = if (visuallyEnabled) ASK_BUTTON_BORDER_ALPHA else 0.2f),
      ),
    contentPadding = PaddingValues(horizontal = 16.dp),
    modifier = modifier.height(CAMERA_INPUT_ROW_HEIGHT),
  ) {
    Icon(
      imageVector = Icons.Default.Mic,
      contentDescription = null,
      modifier = Modifier.size(ButtonDefaults.IconSize),
    )
    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
    Text(
      text = stringResource(R.string.xrteacher_ask_question),
      style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Draws one soft, diffuse glow behind the camera HUD's input row: an elliptical radial gradient
 * centered on the row, spreading [ROW_GLOW_SPREAD_X] past its sides and [ROW_GLOW_SPREAD_Y] above
 * and below, fading from a low opacity in the middle to transparent. It is drawn behind the row's
 * content, so it reads well over any camera background without covering the buttons.
 */
private fun Modifier.softPillGlow(glowColor: Color): Modifier = drawBehind {
  val radiusY = size.height / 2 + ROW_GLOW_SPREAD_Y.toPx()
  val radiusX = size.width / 2 + ROW_GLOW_SPREAD_X.toPx()
  // A circle of radius radiusY, stretched horizontally into an ellipse around the row's center.
  scale(scaleX = radiusX / radiusY, scaleY = 1f, pivot = center) {
    drawCircle(
      brush =
        Brush.radialGradient(
          0f to glowColor.copy(alpha = 0.34f),
          0.45f to glowColor.copy(alpha = 0.22f),
          0.75f to glowColor.copy(alpha = 0.08f),
          1f to Color.Transparent,
          center = center,
          radius = radiusY,
        ),
      radius = radiusY,
      center = center,
    )
  }
}

/** Opacity of the white ring around the "Ask a question" button. */
private const val ASK_BUTTON_BORDER_ALPHA = 0.5f

/** How far the input row's glow spreads past the row's sides. */
private val ROW_GLOW_SPREAD_X = 28.dp

/** How far the input row's glow spreads above and below the row. */
private val ROW_GLOW_SPREAD_Y = 28.dp

/**
 * Shared light-surface [AsrRecordingPillColors] palette used by both the camera HUD recording bar
 * and the active lesson bottom sheet follow-up recording bar.
 */
@Composable
internal fun teacherAsrRecordingPillColors(): AsrRecordingPillColors =
  asrRecordingPillColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    waveformBarColor = MaterialTheme.colorScheme.primary,
    cancelButtonContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    cancelButtonIconTint = MaterialTheme.colorScheme.onSecondaryContainer,
    sendButtonContainerColor = MaterialTheme.colorScheme.primary,
    sendButtonIconTint = MaterialTheme.colorScheme.onPrimary,
  )

/**
 * Pill button to switch between Phone camera mode and Glasses mode.
 *
 * Has two states:
 * - **Connected**: Active, clickable pill with [R.drawable.ic_eyeglasses] icon and `"Switch to
 *   Glasses"`.
 * - **Disconnected**: Greyed-out / disabled pill with muted icon and `"Glasses (Not connected)"`.
 */
@Composable
fun SwitchToXrPill(
  isGlassesConnected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val containerColor =
    MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (isGlassesConnected) 0.87f else 0.53f)
  val borderColor =
    if (isGlassesConnected) MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.15f)
  val contentColor =
    if (isGlassesConnected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.45f)
  val text =
    if (isGlassesConnected) {
      stringResource(R.string.xrteacher_switch_to_xr)
    } else {
      stringResource(R.string.xrteacher_xr_not_connected)
    }

  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 2.dp,
    shadowElevation = 4.dp,
    modifier = modifier.height(36.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_eyeglasses),
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = contentColor,
      )
    }
  }
}
