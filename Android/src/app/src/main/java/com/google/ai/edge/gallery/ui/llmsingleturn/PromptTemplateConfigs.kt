/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.R

enum class PromptTemplateInputEditorType {
  SINGLE_SELECT
}

enum class RewriteToneType(val key: String, @StringRes val labelRes: Int) {
  FORMAL(key = "formal", labelRes = R.string.prompt_lab_tone_formal),
  CASUAL(key = "casual", labelRes = R.string.prompt_lab_tone_casual),
  FRIENDLY(key = "friendly", labelRes = R.string.prompt_lab_tone_friendly),
  POLITE(key = "polite", labelRes = R.string.prompt_lab_tone_polite),
  ENTHUSIASTIC(key = "enthusiastic", labelRes = R.string.prompt_lab_tone_enthusiastic),
  CONCISE(key = "concise", labelRes = R.string.prompt_lab_tone_concise);

  fun toSelectOption() = SelectOption(key = key, labelRes = labelRes)
}

enum class SummarizationType(val key: String, @StringRes val labelRes: Int) {
  KEY_BULLET_POINT(
    key = "key bullet points (3-5)",
    labelRes = R.string.prompt_lab_summary_key_bullet_points,
  ),
  SHORT_PARAGRAPH(
    key = "short paragraph (1-2 sentences)",
    labelRes = R.string.prompt_lab_summary_short_paragraph,
  ),
  CONCISE_SUMMARY(
    key = "concise summary (~50 words)",
    labelRes = R.string.prompt_lab_summary_concise_summary,
  ),
  HEADLINE_TITLE(key = "headline / title", labelRes = R.string.prompt_lab_summary_headline_title),
  ONE_SENTENCE_SUMMARY(
    key = "one-sentence summary",
    labelRes = R.string.prompt_lab_summary_one_sentence,
  );

  fun toSelectOption() = SelectOption(key = key, labelRes = labelRes)
}

enum class LanguageType(val key: String) {
  CPP(key = "C++"),
  JAVA(key = "Java"),
  JAVASCRIPT(key = "JavaScript"),
  KOTLIN(key = "Kotlin"),
  PYTHON(key = "Python"),
  SWIFT(key = "Swift"),
  TYPESCRIPT(key = "TypeScript");

  fun toSelectOption() = SelectOption(key = key, labelFallback = key)
}

data class SelectOption(
  val key: String,
  @StringRes val labelRes: Int = 0,
  val labelFallback: String = "",
)

open class PromptTemplateInputEditor(
  val key: String,
  @StringRes open val labelRes: Int,
  open val type: PromptTemplateInputEditorType,
  open val defaultOptionKey: String = "",
)

/** Single select that shows options in bottom sheet. */
class PromptTemplateSingleSelectInputEditor(
  key: String,
  @StringRes override val labelRes: Int,
  val options: List<SelectOption> = listOf(),
  override val defaultOptionKey: String = "",
) :
  PromptTemplateInputEditor(
    key = key,
    labelRes = labelRes,
    type = PromptTemplateInputEditorType.SINGLE_SELECT,
    defaultOptionKey = defaultOptionKey,
  )

data class PromptTemplateConfig(val inputEditors: List<PromptTemplateInputEditor> = listOf())

private val GEMINI_GRADIENT_STYLE =
  SpanStyle(
    brush = linearGradient(colors = listOf(Color(0xFF4285f4), Color(0xFF9b72cb), Color(0xFFd96570)))
  )

@Suppress("ImmutableEnum")
enum class PromptTemplateType(
  @StringRes val labelRes: Int,
  val config: PromptTemplateConfig,
  val genFullPrompt: (userInput: String, inputEditorValues: Map<String, Any>) -> AnnotatedString =
    { _, _ ->
      AnnotatedString("")
    },
  val examplePrompts: List<String> = listOf(),
) {
  FREE_FORM(
    labelRes = R.string.prompt_lab_tab_free_form,
    config = PromptTemplateConfig(),
    genFullPrompt = { userInput, _ -> AnnotatedString(userInput) },
    examplePrompts =
      listOf(
        "Suggest 3 topics for a podcast about \"Friendships in your 20s\".",
        "Outline the key sections needed in a basic logo design brief.",
        "List 3 pros and 3 cons to consider before buying a smart watch.",
        "Write a short, optimistic quote about the future of technology.",
        "Generate 3 potential names for a mobile app that helps users identify plants.",
        "Explain the difference between AI and machine learning in 2 sentences.",
        "Create a simple haiku about a cat sleeping in the sun.",
        "List 3 ways to make instant noodles taste better using common kitchen ingredients.",
      ),
  ),
  REWRITE_TONE(
    labelRes = R.string.prompt_lab_tab_rewrite_tone,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              key = "tone",
              labelRes = R.string.prompt_lab_label_tone,
              options = RewriteToneType.entries.map { it.toSelectOption() },
              defaultOptionKey = RewriteToneType.FORMAL.key,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val tone = inputEditorValues["tone"] as String
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) {
          append("Rewrite the following text using a ${tone.lowercase()} tone: ")
        }
        append(userInput)
      }
    },
    examplePrompts =
      listOf(
        "Hey team, just wanted to remind everyone about the meeting tomorrow @ 10. Be there!",
        "Our new software update includes several bug fixes and performance improvements.",
        "Due to the fact that the weather was bad, we decided to postpone the event.",
        "Please find attached the requested documentation for your perusal.",
        "Welcome to the team. Review the onboarding materials.",
      ),
  ),
  SUMMARIZE_TEXT(
    labelRes = R.string.prompt_lab_tab_summarize_text,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              key = "style",
              labelRes = R.string.prompt_lab_label_style,
              options = SummarizationType.entries.map { it.toSelectOption() },
              defaultOptionKey = SummarizationType.KEY_BULLET_POINT.key,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val style = inputEditorValues["style"] as String
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) {
          append("Please summarize the following in ${style.lowercase()}: ")
        }
        append(userInput)
      }
    },
    examplePrompts =
      listOf(
        "The new Pixel phone features an advanced camera system with improved low-light performance and AI-powered editing tools. The display is brighter and more energy-efficient. It runs on the latest Tensor chip, offering faster processing and enhanced security features. Battery life has also been extended, providing all-day power for most users.",
        "Beginning this Friday, January 24, giant pandas Bao Li and Qing Bao are officially on view to the public at the Smithsonian’s National Zoo and Conservation Biology Institute (NZCBI). The 3-year-old bears arrived in Washington this past October, undergoing a quarantine period before making their debut. Under NZCBI’s new agreement with the CWCA, Qing Bao and Bao Li will remain in the United States for ten years, until April 2034, in exchange for an annual fee of \$1 million. The pair are still too young to breed, as pandas only reach sexual maturity between ages 4 and 7. “Kind of picture them as like awkward teenagers right now,” Lally told WUSA9. “We still have about two years before we would probably even see signs that they’re ready to start mating.”",
      ),
  ),
  CODE_SNIPPET(
    labelRes = R.string.prompt_lab_tab_code_snippet,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              key = "language",
              labelRes = R.string.prompt_lab_label_language,
              options = LanguageType.entries.map { it.toSelectOption() },
              defaultOptionKey = LanguageType.JAVASCRIPT.key,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val language = inputEditorValues["language"] as String
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) { append("Write a $language code snippet to ") }
        append(userInput)
      }
    },
    examplePrompts =
      listOf(
        "Create an alert box that says \"Hello, World!\"",
        "Declare an immutable variable named 'appName' with the value \"AI Gallery\"",
        "Print the numbers from 1 to 5 using a for loop.",
        "Write a function that returns the square of an integer input.",
      ),
  ),
}
