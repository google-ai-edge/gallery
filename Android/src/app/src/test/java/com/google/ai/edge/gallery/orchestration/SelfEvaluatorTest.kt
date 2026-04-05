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

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelfEvaluatorTest {

  private lateinit var evaluator: SelfEvaluator

  @Before
  fun setup() {
    evaluator = SelfEvaluator()
  }

  @Test
  fun parseEvaluation_goalAchieved() {
    val llmOutput = """
      ```json
      {
        "goalAchieved": true,
        "assessment": "All steps completed successfully",
        "missingItems": [],
        "shouldReplan": false
      }
      ```
    """.trimIndent()

    val result = evaluator.parseEvaluation(llmOutput)

    assertTrue(result.goalAchieved)
    assertEquals("All steps completed successfully", result.assessment)
    assertTrue(result.missingItems.isEmpty())
    assertFalse(result.shouldReplan)
  }

  @Test
  fun parseEvaluation_needsReplan() {
    val llmOutput = """
      ```json
      {
        "goalAchieved": false,
        "assessment": "Only found 2 of 3 facts",
        "missingItems": ["third fact about Mars", "proper formatting"],
        "shouldReplan": true
      }
      ```
    """.trimIndent()

    val result = evaluator.parseEvaluation(llmOutput)

    assertFalse(result.goalAchieved)
    assertEquals("Only found 2 of 3 facts", result.assessment)
    assertEquals(2, result.missingItems.size)
    assertEquals("third fact about Mars", result.missingItems[0])
    assertEquals("proper formatting", result.missingItems[1])
    assertTrue(result.shouldReplan)
  }

  @Test
  fun parseEvaluation_rawJsonNoFence() {
    val llmOutput = """
      {"goalAchieved": true, "assessment": "done", "missingItems": [], "shouldReplan": false}
    """.trimIndent()

    val result = evaluator.parseEvaluation(llmOutput)

    assertTrue(result.goalAchieved)
  }

  @Test
  fun parseEvaluation_malformedFallbackPositive() {
    val llmOutput = "The goal was achieved successfully. All tasks completed and satisfied."

    val result = evaluator.parseEvaluation(llmOutput)

    assertTrue(result.goalAchieved)
    assertTrue(result.missingItems.isEmpty())
    assertFalse(result.shouldReplan)
  }

  @Test
  fun parseEvaluation_malformedFallbackNegative() {
    val llmOutput = "The task failed. Results are incomplete and missing key information."

    val result = evaluator.parseEvaluation(llmOutput)

    assertFalse(result.goalAchieved)
    assertTrue(result.missingItems.isNotEmpty())
    assertTrue(result.shouldReplan)
  }

  @Test
  fun parseEvaluation_emptyInput() {
    val result = evaluator.parseEvaluation("")

    // Empty input → fallback → no positive/negative signals → tied → goalAchieved = false
    assertNotNull(result)
  }

  @Test
  fun buildEvaluationPrompt_includesContext() {
    val plan = ExecutionPlan(
      goal = "test goal",
      reasoning = "test reasoning",
      steps = listOf(
        PlanStep(id = "s1", description = "do thing"),
        PlanStep(id = "s2", description = "do other thing", dependsOn = listOf("s1")),
      ),
    )
    val results = mapOf(
      "s1" to StepResult(stepId = "s1", status = StepStatus.COMPLETED, output = "result 1"),
      "s2" to StepResult(stepId = "s2", status = StepStatus.FAILED, error = "timeout"),
    )

    val prompt = evaluator.buildEvaluationPrompt("test goal", plan, results)

    assertTrue(prompt.contains("test goal"))
    assertTrue(prompt.contains("result 1"))
    assertTrue(prompt.contains("COMPLETED"))
    assertTrue(prompt.contains("FAILED"))
    assertTrue(prompt.contains("timeout"))
  }
}
