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
class PlannerTest {

  private lateinit var planner: Planner

  @Before
  fun setup() {
    planner = Planner()
  }

  // ---- parsePlan tests ----

  @Test
  fun parsePlan_validJson() {
    val llmOutput = """
      ```json
      {
        "goal": "Look up population of Tokyo",
        "reasoning": "Need to use wikipedia skill",
        "steps": [
          {
            "id": "step_1",
            "description": "Load wikipedia skill",
            "skillName": "wikipedia",
            "toolName": "loadSkill",
            "toolArgs": {"skillName": "wikipedia"},
            "dependsOn": []
          },
          {
            "id": "step_2",
            "description": "Search for Tokyo population",
            "skillName": "wikipedia",
            "toolName": "runJs",
            "toolArgs": {"skillName": "wikipedia", "query": "Tokyo population"},
            "dependsOn": ["step_1"]
          }
        ]
      }
      ```
    """.trimIndent()

    val plan = planner.parsePlan(llmOutput, "Look up population of Tokyo")

    assertEquals("Look up population of Tokyo", plan.goal)
    assertEquals("Need to use wikipedia skill", plan.reasoning)
    assertEquals(2, plan.steps.size)
    assertEquals("step_1", plan.steps[0].id)
    assertEquals("loadSkill", plan.steps[0].toolName)
    assertEquals("wikipedia", plan.steps[0].skillName)
    assertEquals(emptyList<String>(), plan.steps[0].dependsOn)
    assertEquals("step_2", plan.steps[1].id)
    assertEquals(listOf("step_1"), plan.steps[1].dependsOn)
  }

  @Test
  fun parsePlan_malformedJson() {
    val llmOutput = """
      Here's my plan:
      Step 1: Load the wikipedia skill
      Step 2: Search for Tokyo population
      Step 3: Summarize the results
    """.trimIndent()

    val plan = planner.parsePlan(llmOutput, "Look up Tokyo")

    assertTrue(plan.steps.isNotEmpty())
    assertEquals("Look up Tokyo", plan.goal)
  }

  @Test
  fun parsePlan_emptyGarbage() {
    val plan = planner.parsePlan("!@#\$%^&*()", "my goal")

    assertEquals(1, plan.steps.size)
    assertEquals("step_1", plan.steps[0].id)
    assertTrue(plan.steps[0].description.contains("my goal"))
  }

  @Test
  fun parsePlan_missingOptionalFields() {
    val llmOutput = """
      ```json
      {
        "goal": "test",
        "reasoning": "",
        "steps": [
          {
            "id": "step_1",
            "description": "Do something"
          }
        ]
      }
      ```
    """.trimIndent()

    val plan = planner.parsePlan(llmOutput, "test")

    assertEquals(1, plan.steps.size)
    assertNull(plan.steps[0].skillName)
    assertNull(plan.steps[0].toolName)
    assertEquals(emptyMap<String, String>(), plan.steps[0].toolArgs)
    assertEquals(emptyList<String>(), plan.steps[0].dependsOn)
  }

  @Test
  fun parsePlan_rawJsonWithoutCodeFence() {
    val llmOutput = """
      {"goal": "test goal", "reasoning": "simple", "steps": [{"id": "s1", "description": "do it", "skillName": null, "toolName": null, "toolArgs": {}, "dependsOn": []}]}
    """.trimIndent()

    val plan = planner.parsePlan(llmOutput, "test goal")

    assertEquals("test goal", plan.goal)
    assertEquals(1, plan.steps.size)
  }

  // ---- getExecutionBatches tests ----

  @Test
  fun getExecutionBatches_linearChain() {
    val plan = ExecutionPlan(
      goal = "test",
      reasoning = "",
      steps = listOf(
        PlanStep(id = "a", description = "A"),
        PlanStep(id = "b", description = "B", dependsOn = listOf("a")),
        PlanStep(id = "c", description = "C", dependsOn = listOf("b")),
      ),
    )

    val batches = planner.getExecutionBatches(plan)

    assertEquals(3, batches.size)
    assertEquals(listOf("a"), batches[0].map { it.id })
    assertEquals(listOf("b"), batches[1].map { it.id })
    assertEquals(listOf("c"), batches[2].map { it.id })
  }

  @Test
  fun getExecutionBatches_parallel() {
    val plan = ExecutionPlan(
      goal = "test",
      reasoning = "",
      steps = listOf(
        PlanStep(id = "a", description = "A"),
        PlanStep(id = "b", description = "B"),
        PlanStep(id = "c", description = "C"),
      ),
    )

    val batches = planner.getExecutionBatches(plan)

    assertEquals(1, batches.size)
    assertEquals(3, batches[0].size)
    assertEquals(setOf("a", "b", "c"), batches[0].map { it.id }.toSet())
  }

  @Test
  fun getExecutionBatches_diamondDag() {
    // A → {B, C} → D
    val plan = ExecutionPlan(
      goal = "test",
      reasoning = "",
      steps = listOf(
        PlanStep(id = "a", description = "A"),
        PlanStep(id = "b", description = "B", dependsOn = listOf("a")),
        PlanStep(id = "c", description = "C", dependsOn = listOf("a")),
        PlanStep(id = "d", description = "D", dependsOn = listOf("b", "c")),
      ),
    )

    val batches = planner.getExecutionBatches(plan)

    assertEquals(3, batches.size)
    assertEquals(listOf("a"), batches[0].map { it.id })
    assertEquals(setOf("b", "c"), batches[1].map { it.id }.toSet())
    assertEquals(listOf("d"), batches[2].map { it.id })
  }

  @Test
  fun getExecutionBatches_cycleDetection() {
    val plan = ExecutionPlan(
      goal = "test",
      reasoning = "",
      steps = listOf(
        PlanStep(id = "a", description = "A", dependsOn = listOf("b")),
        PlanStep(id = "b", description = "B", dependsOn = listOf("a")),
      ),
    )

    // Should not hang — cycle gets broken into one batch
    val batches = planner.getExecutionBatches(plan)

    assertTrue(batches.isNotEmpty())
    val allIds = batches.flatMap { batch -> batch.map { it.id } }.toSet()
    assertEquals(setOf("a", "b"), allIds)
  }

  @Test
  fun getExecutionBatches_emptyPlan() {
    val plan = ExecutionPlan(goal = "test", reasoning = "", steps = emptyList())

    val batches = planner.getExecutionBatches(plan)

    assertTrue(batches.isEmpty())
  }

  // ---- buildPlanningPrompt tests ----

  @Test
  fun buildPlanningPrompt_includesSkills() {
    val skills = listOf(
      SkillSummary("wikipedia", "Search Wikipedia"),
      SkillSummary("qr-code", "Generate QR codes"),
    )

    val prompt = planner.buildPlanningPrompt("test request", skills)

    assertTrue(prompt.contains("wikipedia"))
    assertTrue(prompt.contains("Search Wikipedia"))
    assertTrue(prompt.contains("qr-code"))
    assertTrue(prompt.contains("Generate QR codes"))
    assertTrue(prompt.contains("test request"))
  }

  @Test
  fun buildPlanningPrompt_noSkills() {
    val prompt = planner.buildPlanningPrompt("test", emptyList())

    assertTrue(prompt.contains("No skills available"))
  }

  @Test
  fun buildReplanPrompt_includesPriorResults() {
    val prevPlan = ExecutionPlan(
      goal = "test",
      reasoning = "original reasoning",
      steps = listOf(PlanStep(id = "s1", description = "step 1")),
    )
    val results = mapOf(
      "s1" to StepResult(stepId = "s1", status = StepStatus.COMPLETED, output = "result output"),
    )
    val eval = EvaluationResult(
      goalAchieved = false,
      assessment = "missing details",
      missingItems = listOf("item A", "item B"),
      shouldReplan = true,
    )

    val prompt = planner.buildReplanPrompt("test", prevPlan, results, eval)

    assertTrue(prompt.contains("result output"))
    assertTrue(prompt.contains("missing details"))
    assertTrue(prompt.contains("item A"))
    assertTrue(prompt.contains("item B"))
    assertTrue(prompt.contains("original reasoning"))
  }
}
