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
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGPlanner"

/**
 * Module 1: Planner.
 *
 * Takes a user message + available skills and produces a structured [ExecutionPlan] by asking the
 * LLM to output JSON. Also handles re-planning after a failed evaluation.
 */
class Planner {

  /** Build a prompt that instructs the LLM to output a JSON execution plan. */
  fun buildPlanningPrompt(userMessage: String, skills: List<SkillSummary>): String {
    val skillList =
      if (skills.isEmpty()) "No skills available."
      else skills.joinToString("\n") { skill ->
        val base = "- ${skill.name}: ${skill.description}"
        if (skill.instructions.isNotEmpty()) {
          // Extract only data field names from instructions to keep prompt short.
          val fields = extractDataFields(skill.instructions)
          if (fields.isNotEmpty()) "$base (data fields: $fields)" else base
        } else base
      }

    return """
You are a task planner. Given a user request and available skills, produce an execution plan as JSON.

Available skills:
$skillList

Tools you can use in steps:
- runJs: Execute a skill's JS script. toolArgs MUST include: "skillName" (the skill name), "scriptName" (use "index.html"), "data" (JSON string with the input for the skill).
- runIntent: Trigger an Android intent. toolArgs MUST include: "intent" and "parameters".

IMPORTANT rules for runJs:
- The "data" field must be a JSON string with the skill's expected input fields.
- Pass the user's input data directly in "data". Example: {"skillName":"get-weather","scriptName":"index.html","data":"{\"city\":\"Tokyo\"}"}
- If a step depends on a previous step's output, use null for toolName (LLM reasoning step) or describe what data to pass.

Rules:
- Each step must have: id, description, skillName, toolName, toolArgs, dependsOn
- toolName is one of: "runJs", "runIntent", or null (for LLM-only reasoning steps)
- dependsOn is a list of step IDs that must complete before this step runs
- Steps with no dependencies can run in parallel
- Keep the plan minimal

User request: "$userMessage"

Respond with ONLY valid JSON:
```json
{
  "goal": "the user's goal",
  "reasoning": "brief explanation",
  "steps": [
    {
      "id": "step_1",
      "description": "what this step does",
      "skillName": "skill-name or null",
      "toolName": "runJs or runIntent or null",
      "toolArgs": {"skillName":"x","scriptName":"index.html","data":"{\"key\":\"value\"}"},
      "dependsOn": []
    }
  ]
}
```
""".trimIndent()
  }

  /** Build a prompt for re-planning after a failed evaluation. */
  fun buildReplanPrompt(
    userMessage: String,
    prevPlan: ExecutionPlan,
    results: Map<String, StepResult>,
    evaluation: EvaluationResult,
    skills: List<SkillSummary> = emptyList(),
  ): String {
    val resultsStr =
      results.entries.joinToString("\n") { (id, r) ->
        "- $id (${r.status}): ${r.output.take(200)}${if (r.error != null) " [error: ${r.error}]" else ""}"
      }

    val missingStr = evaluation.missingItems.joinToString("\n") { "- $it" }

    val skillList =
      if (skills.isEmpty()) ""
      else "\nAvailable skills (you MUST use these exact skill names):\n" +
        skills.joinToString("\n") { skill ->
          val base = "- ${skill.name}: ${skill.description}"
          if (skill.instructions.isNotEmpty()) {
            val fields = extractDataFields(skill.instructions)
            if (fields.isNotEmpty()) "$base (data fields: $fields)" else base
          } else base
        } + "\n"

    return """
You are a task planner. A previous plan did not fully achieve the user's goal. Create a revised plan.

User request: "$userMessage"
$skillList
Previous plan reasoning: ${prevPlan.reasoning}

Previous results:
$resultsStr

Evaluation: ${evaluation.assessment}

Missing items:
$missingStr

IMPORTANT: Use ONLY the skill names from the available skills list above. Do not invent new skill names.
Fix the errors from the previous attempt. For runJs, "data" must be a JSON string with the skill's input.

Create a NEW plan. Respond with ONLY valid JSON:
```json
{
  "goal": "the user's goal",
  "reasoning": "explanation of revised plan",
  "steps": [
    {
      "id": "step_1",
      "description": "what this step does",
      "skillName": "skill-name or null",
      "toolName": "runJs or runIntent or null",
      "toolArgs": {"skillName":"x","scriptName":"index.html","data":"{\"key\":\"value\"}"},
      "dependsOn": []
    }
  ]
}
```
""".trimIndent()
  }

  /**
   * Parse LLM output into an [ExecutionPlan].
   *
   * Tries JSON parsing first, then falls back to regex extraction for malformed output.
   */
  fun parsePlan(llmOutput: String, originalGoal: String): ExecutionPlan {
    // Try to extract JSON from the output (may be wrapped in markdown code blocks).
    val jsonStr = extractJson(llmOutput)
    if (jsonStr != null) {
      try {
        return parseJsonPlan(jsonStr, originalGoal)
      } catch (e: Exception) {
        Log.w(TAG, "JSON parsing failed, trying regex fallback: ${e.message}")
      }
    }

    // Regex fallback: try to extract steps from semi-structured text.
    return regexFallbackParse(llmOutput, originalGoal)
  }

  /**
   * Topologically sort plan steps and group independent steps into parallel batches.
   *
   * Returns a list of batches. Steps within a batch have no mutual dependencies and can run in
   * parallel. Batches must execute sequentially.
   */
  fun getExecutionBatches(plan: ExecutionPlan): List<List<PlanStep>> {
    if (plan.steps.isEmpty()) return emptyList()

    val stepsById = plan.steps.associateBy { it.id }
    val inDegree = mutableMapOf<String, Int>()
    val dependents = mutableMapOf<String, MutableList<String>>()

    // Initialize.
    for (step in plan.steps) {
      inDegree[step.id] = 0
      dependents[step.id] = mutableListOf()
    }

    // Build dependency graph.
    for (step in plan.steps) {
      for (dep in step.dependsOn) {
        if (stepsById.containsKey(dep)) {
          inDegree[step.id] = (inDegree[step.id] ?: 0) + 1
          dependents[dep]?.add(step.id)
        }
      }
    }

    val batches = mutableListOf<List<PlanStep>>()
    val remaining = inDegree.toMutableMap()

    while (remaining.isNotEmpty()) {
      // Collect all steps with in-degree 0 — these form the next parallel batch.
      val ready = remaining.filter { it.value == 0 }.keys.toList()

      if (ready.isEmpty()) {
        // Cycle detected — break it by taking all remaining steps.
        Log.w(TAG, "Cycle detected in plan dependencies, forcing remaining steps into one batch")
        batches.add(remaining.keys.mapNotNull { stepsById[it] })
        break
      }

      batches.add(ready.mapNotNull { stepsById[it] })

      // Remove ready steps and decrement dependents.
      for (id in ready) {
        remaining.remove(id)
        for (depId in dependents[id] ?: emptyList()) {
          remaining[depId] = (remaining[depId] ?: 1) - 1
        }
      }
    }

    return batches
  }

  // ---- Private helpers ----

  /**
   * Extract data field names and types from skill instructions.
   * Looks for patterns like "- **fieldName**: description" or "- fieldName: description".
   */
  private fun extractDataFields(instructions: String): String {
    val fieldRegex = Regex("""[-*]\s*\*?\*?(\w+)\*?\*?\s*:\s*([^.\n]{0,60})""")
    val matches = fieldRegex.findAll(instructions)
    val fields = matches.mapNotNull { match ->
      val name = match.groupValues[1].lowercase()
      val desc = match.groupValues[2].trim()
      // Filter out common non-field headings.
      if (name in setOf("instructions", "examples", "constraints", "description", "name")) null
      else "$name ($desc)"
    }.take(5).toList()
    return fields.joinToString(", ")
  }

  /** Extract JSON object from LLM output, handling markdown code fences. */
  private fun extractJson(text: String): String? {
    // Try markdown code block first.
    val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
    codeBlockRegex.find(text)?.let { return it.groupValues[1].trim() }

    // Try raw JSON object.
    val jsonRegex = Regex("(\\{\\s*\"goal\".*\\})", RegexOption.DOT_MATCHES_ALL)
    jsonRegex.find(text)?.let { return it.groupValues[1].trim() }

    return null
  }

  /** Parse a clean JSON string into an ExecutionPlan. */
  private fun parseJsonPlan(jsonStr: String, originalGoal: String): ExecutionPlan {
    val json = JSONObject(jsonStr)
    val goal = json.optString("goal", originalGoal)
    val reasoning = json.optString("reasoning", "")
    val stepsArray = json.getJSONArray("steps")

    val steps = mutableListOf<PlanStep>()
    for (i in 0 until stepsArray.length()) {
      val stepJson = stepsArray.getJSONObject(i)
      val toolArgs = mutableMapOf<String, String>()
      val argsJson = stepJson.optJSONObject("toolArgs")
      if (argsJson != null) {
        for (key in argsJson.keys()) {
          toolArgs[key] = argsJson.getString(key)
        }
      }

      val dependsOn = mutableListOf<String>()
      val depsArray = stepJson.optJSONArray("dependsOn")
      if (depsArray != null) {
        for (j in 0 until depsArray.length()) {
          dependsOn.add(depsArray.getString(j))
        }
      }

      steps.add(
        PlanStep(
          id = stepJson.getString("id"),
          description = stepJson.optString("description", ""),
          skillName = stepJson.optString("skillName").takeIf { it.isNotEmpty() && it != "null" },
          toolName = stepJson.optString("toolName").takeIf { it.isNotEmpty() && it != "null" },
          toolArgs = toolArgs,
          dependsOn = dependsOn,
        )
      )
    }

    Log.d(TAG, "Parsed plan: goal=$goal, ${steps.size} steps")
    for (step in steps) {
      Log.d(TAG, "  Step ${step.id}: tool=${step.toolName}, skill=${step.skillName}, args=${step.toolArgs}, deps=${step.dependsOn}")
    }
    return ExecutionPlan(goal = goal, reasoning = reasoning, steps = steps)
  }

  /** Fallback parser for when the LLM produces semi-structured but not valid JSON output. */
  private fun regexFallbackParse(text: String, originalGoal: String): ExecutionPlan {
    Log.d(TAG, "Using regex fallback to parse plan")

    // Try to extract individual step-like patterns.
    val stepRegex = Regex("(?:step|\\d+)[_\\s]*(\\d+)[:\\s]+(.+?)(?=(?:step|\\d+)[_\\s]*\\d+[:]|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val matches: List<MatchResult> = stepRegex.findAll(text).toList()

    val steps =
      if (matches.isNotEmpty()) {
        matches.mapIndexed { index: Int, match: MatchResult ->
          PlanStep(
            id = "step_${index + 1}",
            description = match.groupValues[2].trim().take(200),
            dependsOn = if (index > 0) listOf("step_$index") else emptyList(),
          )
        }
      } else {
        // Last resort: treat the entire output as a single step.
        listOf(
          PlanStep(
            id = "step_1",
            description = "Execute user request: $originalGoal",
          )
        )
      }

    return ExecutionPlan(
      goal = originalGoal,
      reasoning = "Plan extracted via fallback parsing",
      steps = steps,
    )
  }
}
