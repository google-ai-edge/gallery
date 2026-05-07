package com.appswithlove.ai.customtasks.emoji

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appswithlove.ai.R
import com.appswithlove.ai.ui.llmchat.LlmChatModelHelper
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEBOUNCE_MS = 500L

private const val PROMPT_PREFIX = "Text: "
private const val PROMPT_SUFFIX = "\nEmoji:"

@OptIn(FlowPreview::class)
@Composable
fun EmojiScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel

  var inputText by remember { mutableStateOf("") }
  var currentEmoji by remember { mutableStateOf<String?>(null) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  val inputFlow = remember { MutableStateFlow("") }
  val inferenceMutex = remember { Mutex() }
  val context = LocalContext.current

  // Show loading indicator before the model is initialized.
  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
    return
  }

  // Debounced inference trigger.
  LaunchedEffect(Unit) {
    inputFlow
      .debounce(DEBOUNCE_MS)
      .filter { it.isNotEmpty() }
      .collect { text ->
        isLoading = true
        errorMessage = null
        inferenceMutex.withLock {
          try {
            // Cancel any in-flight inference before resetting.
            try {
              LlmChatModelHelper.stopResponse(model = model)
            } catch (_: Exception) {}

            // Reset conversation before each query so system prompt is fresh.
            LlmChatModelHelper.resetConversation(
              model = model,
              systemInstruction = com.google.ai.edge.litertlm.Contents.of(
                listOf(com.google.ai.edge.litertlm.Content.Text(
                  "You are an emoji assistant. When given a text after 'Text:', respond with ONLY a single emoji on the 'Emoji:' line. " +
                    "Examples:\nText: I love you\nEmoji: \u2764\uFE0F\nText: It's cold\nEmoji: \uD83E\uDD76\nText: I'm happy\nEmoji: \uD83D\uDE00"
                ))
              ),
            )
            val prompt = PROMPT_PREFIX + text + PROMPT_SUFFIX
            val accumulated = StringBuilder()
            LlmChatModelHelper.runInference(
              model = model,
              input = prompt,
              resultListener = { partialResult, done, _ ->
                accumulated.append(partialResult)
                if (done) {
                  val emoji = accumulated.toString().firstEmoji()
                  currentEmoji = emoji
                  isLoading = false
                }
              },
              cleanUpListener = {},
              onError = { msg ->
                isLoading = false
                errorMessage = msg
              },
            )
          } catch (e: Exception) {
            isLoading = false
            errorMessage = context.getString(R.string.emoji_model_error)
          }
        }
      }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = bottomPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    // Emoji display circle.
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.size(180.dp),
    ) {
      Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
              trackColor = MaterialTheme.colorScheme.surface,
              strokeWidth = 3.dp,
              modifier = Modifier.size(32.dp),
            )
            Text(
              text = stringResource(R.string.emoji_loading),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        } else if (errorMessage != null) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "\u26A0\uFE0F",
              fontSize = 48.sp,
              textAlign = TextAlign.Center,
            )
            Text(
              text = errorMessage!!,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp),
            )
          }
        } else if (currentEmoji != null) {
          Text(
            text = currentEmoji!!,
            fontSize = 80.sp,
            textAlign = TextAlign.Center,
          )
        } else {
          Text(
            text = "\uD83D\uDE36",
            fontSize = 80.sp,
            textAlign = TextAlign.Center,
          )
        }
      }
    }

    // Text input.
    OutlinedTextField(
      value = inputText,
      onValueChange = { newText ->
        inputText = newText
        inputFlow.value = newText
        errorMessage = null
        if (newText.isEmpty()) {
          currentEmoji = null
          isLoading = false
        }
      },
      label = { Text(stringResource(R.string.emoji_input_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
    )
  }
}
