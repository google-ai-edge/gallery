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

package com.google.ai.edge.gallery.orchestration

import android.util.Log
import org.json.JSONObject

private const val TAG = "AGSelfEvaluator"

/**
 * Module 3: Self-Evaluator.
 *
 * After execution completes, evaluates whether the user's goal was achieved. If not, indicates what
 * is missing and whether re-planning should occur.
 */
class SelfEvaluator {

  /** Build a prompt asking the LLM to evaluate the execution results against the original goal. */
  fun buildEvaluationPrompt(
    goal: String,
    plan: ExecutionPlan,
    results: Map<String, StepResult>,
  ): String {
    val stepsStr =
      plan.steps.joinToString("\n") { step ->
        val result = results[step.id]
        val status = result?.status?.name ?: "UNKNOWN"
        val output = result?.output?.take(300) ?: "no output"
        val error = if (result?.error != null) " [error: ${result.error}]" else ""
        "- ${step.id} (${step.description}): $status — $output$error"
      }

    return """
You are an evaluator. Determine if the user's goal was achieved by the execution results.

User's goal: "$goal"

Plan reasoning: ${plan.reasoning}

Execution results:
$stepsStr

Evaluate the results and respond with ONLY valid JSON:
```json
{
  "goalAchieved": true or false,
  "assessment": "brief explanation of what was achieved or what went wrong",
  "missingItems": ["item 1 that is still needed", "item 2"],
  "shouldReplan": true or false
}
```

Rules:
- goalAchieved = true if the user's request is fully satisfied
- If most steps succeeded and the output is useful, be generous — mark goalAchieved = true
- missingItems should be empty if goalAchieved is true
- shouldReplan = true only if there is a clear path to improvement with a revised plan
- shouldReplan = false if the failure is unrecoverable (e.g., skill not available)
""".trimIndent()
  }

  /**
   * Parse LLM evaluation output into an [EvaluationResult].
   *
   * Tries JSON parsing first, then falls back to heuristic text analysis.
   */
  fun parseEvaluation(llmOutput: String): EvaluationResult {
    val jsonStr = extractJson(llmOutput)
    if (jsonStr != null) {
      try {
        return parseJsonEvaluation(jsonStr)
      } catch (e: Exception) {
        Log.w(TAG, "JSON parsing failed for evaluation, using fallback: ${e.message}")
      }
    }

    return fallbackParseEvaluation(llmOutput)
  }

  // ---- Private helpers ----

  private fun extractJson(text: String): String? {
    val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
    codeBlockRegex.find(text)?.let { return it.groupValues[1].trim() }

    val jsonRegex = Regex("(\\{\\s*\"goalAchieved\".*})", RegexOption.DOT_MATCHES_ALL)
    jsonRegex.find(text)?.let { return it.groupValues[1].trim() }

    return null
  }

  private fun parseJsonEvaluation(jsonStr: String): EvaluationResult {
    val json = JSONObject(jsonStr)

    val missingItems = mutableListOf<String>()
    val missingArray = json.optJSONArray("missingItems")
    if (missingArray != null) {
      for (i in 0 until missingArray.length()) {
        missingItems.add(missingArray.getString(i))
      }
    }

    return EvaluationResult(
      goalAchieved = json.getBoolean("goalAchieved"),
      assessment = json.optString("assessment", ""),
      missingItems = missingItems,
      shouldReplan = json.optBoolean("shouldReplan", false),
    )
  }

  /** Heuristic fallback: check for positive/negative keywords in the text. */
  private fun fallbackParseEvaluation(text: String): EvaluationResult {
    Log.d(TAG, "Using fallback evaluation parsing")
    val lower = text.lowercase()

    val positiveSignals =
      listOf("achieved", "success", "completed", "satisfied", "goal met", "true")
    val negativeSignals = listOf("not achieved", "failed", "missing", "incomplete", "false")

    val positiveCount = positiveSignals.count { lower.contains(it) }
    val negativeCount = negativeSignals.count { lower.contains(it) }

    val goalAchieved = positiveCount > negativeCount

    return EvaluationResult(
      goalAchieved = goalAchieved,
      assessment = text.take(300),
      missingItems = if (!goalAchieved) listOf("Could not parse specific missing items") else emptyList(),
      shouldReplan = !goalAchieved,
    )
  }
}
