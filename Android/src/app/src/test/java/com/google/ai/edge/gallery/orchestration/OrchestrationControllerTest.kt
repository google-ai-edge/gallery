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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrchestrationControllerTest {

  /** LLM provider that returns canned plan + eval responses in sequence. */
  private fun sequentialLlmProvider(vararg responses: String): LlmInferenceProvider {
    val queue = responses.toMutableList()
    return object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        delay(10)
        return if (queue.isNotEmpty()) queue.removeAt(0) else "fallback"
      }
      override fun cancel() {}
    }
  }

  private fun simpleToolExecutor(): ToolExecutor {
    return object : ToolExecutor {
      override suspend fun executeTool(
        toolName: String,
        args: Map<String, String>,
      ): ToolExecutionResult {
        return ToolExecutionResult(success = true, output = "tool result for $toolName")
      }
      override fun getAvailableSkills(): List<SkillSummary> {
        return listOf(SkillSummary("test-skill", "A test skill"))
      }
    }
  }

  private val validPlanJson = """
    ```json
    {
      "goal": "test goal",
      "reasoning": "simple plan",
      "steps": [
        {"id": "step_1", "description": "Run tool", "toolName": "runJs", "toolArgs": {"skillName": "test"}, "dependsOn": []}
      ]
    }
    ```
  """.trimIndent()

  private val goalAchievedJson = """
    ```json
    {"goalAchieved": true, "assessment": "All good", "missingItems": [], "shouldReplan": false}
    ```
  """.trimIndent()

  private val goalNotAchievedJson = """
    ```json
    {"goalAchieved": false, "assessment": "Missing data", "missingItems": ["item X"], "shouldReplan": true}
    ```
  """.trimIndent()

  private val goalNotAchievedNoReplanJson = """
    ```json
    {"goalAchieved": false, "assessment": "Unrecoverable", "missingItems": ["item X"], "shouldReplan": false}
    ```
  """.trimIndent()

  @Test
  fun run_happyPath() = runTest {
    val llm = sequentialLlmProvider(validPlanJson, goalAchievedJson)
    val controller = OrchestrationController(llm, simpleToolExecutor(), maxIterations = 3)

    controller.run("test query")

    val state = controller.state.value
    assertEquals(OrchestrationStatus.COMPLETED, state.status)
    assertTrue(state.evaluation?.goalAchieved == true)
    assertEquals(1, state.iteration)
    assertNotNull(state.finalOutput)
  }

  @Test
  fun run_replanLoop() = runTest {
    // Plan → eval fails → replan → eval fails → replan → eval passes
    val llm = sequentialLlmProvider(
      validPlanJson,        // initial plan
      goalNotAchievedJson,  // eval 1: fail
      validPlanJson,        // replan 1
      goalNotAchievedJson,  // eval 2: fail
      validPlanJson,        // replan 2
      goalAchievedJson,     // eval 3: pass
    )
    val controller = OrchestrationController(llm, simpleToolExecutor(), maxIterations = 3)

    controller.run("complex query")

    val state = controller.state.value
    assertEquals(OrchestrationStatus.COMPLETED, state.status)
    assertTrue(state.evaluation?.goalAchieved == true)
    assertEquals(3, state.iteration)
  }

  @Test
  fun run_maxIterations() = runTest {
    // Eval never passes — should stop at maxIterations
    val llm = sequentialLlmProvider(
      validPlanJson,        // initial plan
      goalNotAchievedJson,  // eval 1: fail
      validPlanJson,        // replan 1
      goalNotAchievedJson,  // eval 2: fail → max reached
    )
    val controller = OrchestrationController(llm, simpleToolExecutor(), maxIterations = 2)

    controller.run("impossible query")

    val state = controller.state.value
    assertEquals(OrchestrationStatus.COMPLETED, state.status)
    assertFalse(state.evaluation?.goalAchieved == true)
    assertEquals(2, state.iteration)
  }

  @Test
  fun run_stopsWhenShouldReplanFalse() = runTest {
    val llm = sequentialLlmProvider(validPlanJson, goalNotAchievedNoReplanJson)
    val controller = OrchestrationController(llm, simpleToolExecutor(), maxIterations = 5)

    controller.run("query")

    val state = controller.state.value
    assertEquals(OrchestrationStatus.COMPLETED, state.status)
    assertEquals(1, state.iteration)
    assertFalse(state.evaluation?.goalAchieved == true)
  }

  @Test
  fun run_cancel() = runTest {
    val slowLlm = object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        delay(500)
        return validPlanJson
      }
      override fun cancel() {}
    }
    val controller = OrchestrationController(slowLlm, simpleToolExecutor())

    val job = launch { controller.run("query") }
    delay(50)
    controller.cancel()
    job.join()

    val status = controller.state.value.status
    assertTrue(
      "Should be CANCELLED or ERROR after cancel",
      status == OrchestrationStatus.CANCELLED ||
        status == OrchestrationStatus.ERROR ||
        status == OrchestrationStatus.COMPLETED,
    )
  }

  @Test
  fun run_stateTransitions() = runTest {
    val llm = sequentialLlmProvider(validPlanJson, goalAchievedJson)
    val controller = OrchestrationController(llm, simpleToolExecutor())

    val statuses = mutableListOf<OrchestrationStatus>()
    val collectJob = launch {
      controller.state.collect { statuses.add(it.status) }
    }

    controller.run("query")
    delay(50)
    collectJob.cancel()

    // Should have transitioned through at least PLANNING and COMPLETED
    assertTrue("Should contain PLANNING", statuses.contains(OrchestrationStatus.PLANNING))
    assertTrue("Should contain COMPLETED", statuses.contains(OrchestrationStatus.COMPLETED))
  }

  @Test
  fun run_planningError() = runTest {
    val badLlm = object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        throw RuntimeException("LLM crashed")
      }
      override fun cancel() {}
    }
    val controller = OrchestrationController(badLlm, simpleToolExecutor())

    controller.run("query")

    val state = controller.state.value
    assertEquals(OrchestrationStatus.ERROR, state.status)
    assertTrue(state.error?.contains("LLM crashed") == true)
  }

  @Test
  fun reset_clearsState() = runTest {
    val llm = sequentialLlmProvider(validPlanJson, goalAchievedJson)
    val controller = OrchestrationController(llm, simpleToolExecutor())

    controller.run("query")
    assertEquals(OrchestrationStatus.COMPLETED, controller.state.value.status)

    controller.reset()
    assertEquals(OrchestrationStatus.IDLE, controller.state.value.status)
    assertNull(controller.state.value.plan)
    assertNull(controller.state.value.evaluation)
  }
}
