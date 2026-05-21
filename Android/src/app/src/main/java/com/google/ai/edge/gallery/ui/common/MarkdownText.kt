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

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genzopia.offlineai.ui.theme.OfflineLLMTheme
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

/** Helper function to convert LaTeX to Unicode mathematical symbols */
private fun convertLatexToUnicode(latex: String): String {
  var result = latex.trim()

  // Greek letters
  val greekMap = mapOf(
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
    "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
    "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
    "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
    "\\Epsilon" to "Ε", "\\Zeta" to "Ζ", "\\Eta" to "Η", "\\Theta" to "Θ",
    "\\Iota" to "Ι", "\\Kappa" to "Κ", "\\Lambda" to "Λ", "\\Mu" to "Μ",
    "\\Nu" to "Ν", "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Rho" to "Ρ",
    "\\Sigma" to "Σ", "\\Tau" to "Τ", "\\Upsilon" to "Υ", "\\Phi" to "Φ",
    "\\Chi" to "Χ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
  )

  // Mathematical operators and symbols
  val symbolMap = mapOf(
    "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
    "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫",
    "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\div" to "÷",
    "\\cdot" to "·", "\\ast" to "∗", "\\star" to "⋆",
    "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\approx" to "≈",
    "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝",
    "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃",
    "\\subseteq" to "⊆", "\\supseteq" to "⊇", "\\cup" to "∪", "\\cap" to "∩",
    "\\emptyset" to "∅", "\\exists" to "∃", "\\forall" to "∀",
    "\\neg" to "¬", "\\wedge" to "∧", "\\vee" to "∨",
    "\\leftarrow" to "←", "\\rightarrow" to "→", "\\leftrightarrow" to "↔",
    "\\Leftarrow" to "⇐", "\\Rightarrow" to "⇒", "\\Leftrightarrow" to "⇔",
    "\\sqrt" to "√", "\\angle" to "∠", "\\degree" to "°",
    "\\therefore" to "∴", "\\because" to "∵",
    "\\ldots" to "…", "\\cdots" to "⋯"
  )

  // Replace all Greek letters and symbols
  greekMap.forEach { (latex, unicode) -> result = result.replace(latex, unicode) }
  symbolMap.forEach { (latex, unicode) -> result = result.replace(latex, unicode) }

  // Handle superscripts (simple cases like ^2, ^n)
  result = result.replace(Regex("""\^(\d)""")) { match ->
    val digit = match.groupValues[1]
    when (digit) {
      "0" -> "⁰"
      "1" -> "¹"
      "2" -> "²"
      "3" -> "³"
      "4" -> "⁴"
      "5" -> "⁵"
      "6" -> "⁶"
      "7" -> "⁷"
      "8" -> "⁸"
      "9" -> "⁹"
      else -> "^$digit"
    }
  }

  // Handle subscripts (simple cases like _1, _n)
  result = result.replace(Regex("""_(\d)""")) { match ->
    val digit = match.groupValues[1]
    when (digit) {
      "0" -> "₀"
      "1" -> "₁"
      "2" -> "₂"
      "3" -> "₃"
      "4" -> "₄"
      "5" -> "₅"
      "6" -> "₆"
      "7" -> "₇"
      "8" -> "₈"
      "9" -> "₉"
      else -> "_$digit"
    }
  }

  // Handle fractions \frac{a}{b} -> (a/b)
  result = result.replace(Regex("""\\frac\{([^}]+)\}\{([^}]+)\}""")) { match ->
    "(${match.groupValues[1]}/${match.groupValues[2]})"
  }

  return result
}

/** Composable to render LaTeX math expressions */
@Suppress("unused")
@Composable
private fun MathExpression(
  latex: String,
  isBlock: Boolean = false,
  textColor: Color = MaterialTheme.colorScheme.onSurface
) {
  val unicodeText = remember(latex) { convertLatexToUnicode(latex) }

  if (isBlock) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .background(
          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          RoundedCornerShape(8.dp)
        )
        .horizontalScroll(rememberScrollState())
        .padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = unicodeText,
        style = TextStyle(
          fontSize = 18.sp,
          fontStyle = FontStyle.Italic,
          color = textColor,
          fontFamily = FontFamily.Serif
        )
      )
    }
  } else {
    Text(
      text = unicodeText,
      style = TextStyle(
        fontSize = 16.sp,
        fontStyle = FontStyle.Italic,
        color = textColor.copy(alpha = 0.9f),
        fontFamily = FontFamily.Serif
      )
    )
  }
}

/** Composable function to display Markdown-formatted text with LaTeX support */
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  smallFontSize: Boolean = false,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  val fontSize = if (smallFontSize) {
    MaterialTheme.typography.bodyMedium.fontSize
  } else {
    MaterialTheme.typography.bodyLarge.fontSize
  }

  // Process text to handle LaTeX expressions
  val processedText = remember(text) {
    var processed = text

    // Replace block math $$...$$ with placeholders
    val blockMathRegex = Regex("""[$][$]([^$]+)[$][$]""")
    val blockMatches = blockMathRegex.findAll(processed).toList()
    blockMatches.forEachIndexed { index, match ->
      processed = processed.replace(match.value, "\n\n[BLOCKMATH_$index]\n\n")
    }

    // Replace inline math $...$ with placeholders
    val inlineMathRegex = Regex("""[$]([^$]+)[$]""")
    val inlineMatches = inlineMathRegex.findAll(processed).toList()
    inlineMatches.forEach { match ->
      val converted = convertLatexToUnicode(match.groupValues[1])
      processed = processed.replace(match.value, converted)
    }

    processed
  }

  Column(modifier = modifier) {
    CompositionLocalProvider {
      ProvideTextStyle(
        TextStyle(
          fontSize = fontSize,
          lineHeight = fontSize * 1.3,
          color = textColor
        )
      ) {
        RichText(
          style = RichTextStyle(
            codeBlockStyle = CodeBlockStyle(
              textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                color = MaterialTheme.colorScheme.onSurface
              ),
              Modifier.padding(8.dp)
            ),
            stringStyle = RichTextStringStyle(
              linkStyle = TextLinkStyles(
                style = SpanStyle(
                  color = DarkTheme_text(),
                  textDecoration = TextDecoration.Underline
                )
              )
            )
          )
        ) {
          Markdown(content = processedText.trimIndent())
        }
      }
    }
  }
}

@Composable
@Preview
fun SimpleTutorialButton() {
  OfflineLLMTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(
          onClick = { /* your click action */ }
        ) {
          Text("Click Me")
        }
      }
    }
  }
}



@Preview(showBackground = true)
@Composable
fun MarkdownTextPreview() {
  OfflineLLMTheme {
    Column(modifier = Modifier.padding(16.dp)) {
      MarkdownText(
        text = """
                # Math Examples
                
                ## Inline Math
                The quadratic formula is ${"$"}x = (-b \pm \sqrt{b^2 - 4ac}) / (2a)${"$"}
                
                Einstein's famous equation: ${"$"}E = mc^2${"$"}
                
                ## Greek Letters
                Common symbols: ${"$"}\alpha${"$"}, ${"$"}\beta${"$"}, ${"$"}\gamma${"$"}, ${"$"}\delta${"$"}, ${"$"}\theta${"$"}, ${"$"}\pi${"$"}, ${"$"}\sigma${"$"}, ${"$"}\omega${"$"}
                
                ## Mathematical Symbols
                - Infinity: ${"$"}\infty${"$"}
                - Integral: ${"$"}\int f(x)dx${"$"}
                - Sum: ${"$"}\sum_{i=1}^{n} x_i${"$"}
                - Product: ${"$"}\prod_{i=1}^{n} x_i${"$"}
                - Less than or equal: ${"$"}x \leq y${"$"}
                - Not equal: ${"$"}x \neq y${"$"}
                - Approximately: ${"$"}x \approx y${"$"}
                - In set: ${"$"}x \in \mathbb{R}${"$"}
                - For all: ${"$"}\forall x${"$"}
                - There exists: ${"$"}\exists x${"$"}
                
                ## Superscripts and Subscripts
                ${"$"}x^2 + y^2 = r^2${"$"}
                
                ${"$"}a_0, a_1, a_2, ..., a_n${"$"}
                """.trimIndent()
      )
    }
  }
}