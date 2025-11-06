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

package com.neuralforge.mobile.ui.llmsingleturn

import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

enum class PromptTemplateInputEditorType {
  SINGLE_SELECT
}

enum class RewriteToneType(val label: String) {
  FORMAL(label = "Formal"),
  CASUAL(label = "Casual"),
  FRIENDLY(label = "Friendly"),
  POLITE(label = "Polite"),
  ENTHUSIASTIC(label = "Enthusiastic"),
  CONCISE(label = "Concise"),
}

enum class SummarizationType(val label: String) {
  KEY_BULLET_POINT(label = "Key bullet points (3-5)"),
  SHORT_PARAGRAPH(label = "Short paragraph (1-2 sentences)"),
  CONCISE_SUMMARY(label = "Concise summary (~50 words)"),
  HEADLINE_TITLE(label = "Headline / title"),
  ONE_SENTENCE_SUMMARY(label = "One-sentence summary"),
}

enum class LanguageType(val label: String) {
  CPP(label = "C++"),
  JAVA(label = "Java"),
  JAVASCRIPT(label = "JavaScript"),
  KOTLIN(label = "Kotlin"),
  PYTHON(label = "Python"),
  SWIFT(label = "Swift"),
  TYPESCRIPT(label = "TypeScript"),
}

enum class InputEditorLabel(val label: String) {
  TONE(label = "Tone"),
  STYLE(label = "Style"),
  LANGUAGE(label = "Language"),
}

open class PromptTemplateInputEditor(
  open val label: String,
  open val type: PromptTemplateInputEditorType,
  open val defaultOption: String = "",
)

/** Single select that shows options in bottom sheet. */
class PromptTemplateSingleSelectInputEditor(
  override val label: String,
  val options: List<String> = listOf(),
  override val defaultOption: String = "",
) :
  PromptTemplateInputEditor(
    label = label,
    type = PromptTemplateInputEditorType.SINGLE_SELECT,
    defaultOption = defaultOption,
  )

data class PromptTemplateConfig(val inputEditors: List<PromptTemplateInputEditor> = listOf())

private val GEMINI_GRADIENT_STYLE =
  SpanStyle(
    brush = linearGradient(colors = listOf(Color(0xFF4285f4), Color(0xFF9b72cb), Color(0xFFd96570)))
  )

@Suppress("ImmutableEnum")
enum class PromptTemplateType(
  val label: String,
  val config: PromptTemplateConfig,
  val genFullPrompt: (userInput: String, inputEditorValues: Map<String, Any>) -> AnnotatedString =
    { _, _ ->
      AnnotatedString("")
    },
  val examplePrompts: List<String> = listOf(),
) {
  FREE_FORM(
    label = "Free form",
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
    label = "Rewrite tone",
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.TONE.label,
              options = RewriteToneType.entries.map { it.label },
              defaultOption = RewriteToneType.FORMAL.label,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val tone = inputEditorValues[InputEditorLabel.TONE.label] as String
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
    label = "Summarize text",
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.STYLE.label,
              options = SummarizationType.entries.map { it.label },
              defaultOption = SummarizationType.KEY_BULLET_POINT.label,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val style = inputEditorValues[InputEditorLabel.STYLE.label] as String
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
    label = "Code snippet",
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.LANGUAGE.label,
              options = LanguageType.entries.map { it.label },
              defaultOption = LanguageType.JAVASCRIPT.label,
            )
          )
      ),
    genFullPrompt = { userInput, inputEditorValues ->
      val language = inputEditorValues[InputEditorLabel.LANGUAGE.label] as String
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
