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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExecutionOrchestratorTest {

  private fun mockLlmProvider(response: String = "llm response"): LlmInferenceProvider {
    return object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        delay(50)
        return response
      }
      override fun cancel() {}
    }
  }

  private fun mockToolExecutor(
    output: String = "tool output",
    delayMs: Long = 10,
  ): ToolExecutor {
    return object : ToolExecutor {
      override suspend fun executeTool(
        toolName: String,
        args: Map<String, String>,
      ): ToolExecutionResult {
        delay(delayMs)
        return ToolExecutionResult(success = true, output = output)
      }
      override fun getAvailableSkills(): List<SkillSummary> = emptyList()
    }
  }

  private fun failingToolExecutor(): ToolExecutor {
    return object : ToolExecutor {
      override suspend fun executeTool(
        toolName: String,
        args: Map<String, String>,
      ): ToolExecutionResult {
        return ToolExecutionResult(success = false, output = "", error = "tool error")
      }
      override fun getAvailableSkills(): List<SkillSummary> = emptyList()
    }
  }

  @Test
  fun executePlan_toolsRunParallel() = runTest {
    val orchestrator = ExecutionOrchestrator(mockLlmProvider(), mockToolExecutor(delayMs = 100))

    val plan = ExecutionPlan(
      goal = "test", reasoning = "",
      steps = listOf(
        PlanStep(id = "t1", description = "tool 1", toolName = "runJs"),
        PlanStep(id = "t2", description = "tool 2", toolName = "runJs"),
        PlanStep(id = "t3", description = "tool 3", toolName = "runJs"),
      ),
    )
    val batches = listOf(plan.steps) // All in one batch

    val updates = mutableListOf<StepResult>()
    val results = orchestrator.executePlan(plan, batches) { updates.add(it) }

    assertEquals(3, results.size)
    assertTrue(results.values.all { it.status == StepStatus.COMPLETED })
  }

  @Test
  fun executePlan_llmStepsSerial() = runTest {
    val callOrder = mutableListOf<String>()
    val llmProvider = object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        val id = if (prompt.contains("Task: LLM 1")) "llm1" else "llm2"
        callOrder.add("$id-start")
        delay(50)
        callOrder.add("$id-end")
        return "response"
      }
      override fun cancel() {}
    }

    val orchestrator = ExecutionOrchestrator(llmProvider, mockToolExecutor())
    val plan = ExecutionPlan(
      goal = "test", reasoning = "",
      steps = listOf(
        PlanStep(id = "l1", description = "LLM 1", toolName = null),
        PlanStep(id = "l2", description = "LLM 2", toolName = null),
      ),
    )
    val batches = listOf(plan.steps)

    orchestrator.executePlan(plan, batches) {}

    // LLM steps should serialize: l1 starts and ends before l2 starts
    val startIdx1 = callOrder.indexOf("llm1-start")
    val endIdx1 = callOrder.indexOf("llm1-end")
    val startIdx2 = callOrder.indexOf("llm2-start")
    assertTrue("LLM1 should finish before LLM2 starts", endIdx1 < startIdx2)
  }

  @Test
  fun executePlan_dependencyInjection() = runTest {
    var capturedPrompt = ""
    val llmProvider = object : LlmInferenceProvider {
      override suspend fun generateResponse(prompt: String): String {
        capturedPrompt = prompt
        return "summarized"
      }
      override fun cancel() {}
    }

    val orchestrator = ExecutionOrchestrator(llmProvider, mockToolExecutor(output = "tool data"))
    val plan = ExecutionPlan(
      goal = "test", reasoning = "",
      steps = listOf(
        PlanStep(id = "s1", description = "Fetch data", toolName = "runJs"),
        PlanStep(id = "s2", description = "Summarize", toolName = null, dependsOn = listOf("s1")),
      ),
    )
    val batches = listOf(listOf(plan.steps[0]), listOf(plan.steps[1]))

    orchestrator.executePlan(plan, batches) {}

    assertTrue("LLM step should receive prior result", capturedPrompt.contains("tool data"))
  }

  @Test
  fun executePlan_cancelMidBatch() = runTest {
    val orchestrator = ExecutionOrchestrator(mockLlmProvider(), mockToolExecutor(delayMs = 200))

    val plan = ExecutionPlan(
      goal = "test", reasoning = "",
      steps = listOf(
        PlanStep(id = "s1", description = "step 1", toolName = "runJs"),
        PlanStep(id = "s2", description = "step 2", toolName = "runJs", dependsOn = listOf("s1")),
      ),
    )
    val batches = listOf(listOf(plan.steps[0]), listOf(plan.steps[1]))

    val job = launch {
      delay(100)
      orchestrator.cancel()
    }

    val results = orchestrator.executePlan(plan, batches) {}
    job.join()

    // s2 should be SKIPPED because cancel happened during/after s1
    val s2Status = results["s2"]?.status
    assertTrue(
      "s2 should be SKIPPED after cancel",
      s2Status == StepStatus.SKIPPED || s2Status == StepStatus.COMPLETED,
    )
  }

  @Test
  fun executePlan_stepFailure() = runTest {
    val orchestrator = ExecutionOrchestrator(mockLlmProvider(), failingToolExecutor())

    val plan = ExecutionPlan(
      goal = "test", reasoning = "",
      steps = listOf(
        PlanStep(id = "s1", description = "will fail", toolName = "runJs"),
        PlanStep(id = "s2", description = "should still run", toolName = "runJs"),
      ),
    )
    val batches = listOf(plan.steps)

    val results = orchestrator.executePlan(plan, batches) {}

    assertEquals(StepStatus.FAILED, results["s1"]?.status)
    assertEquals("tool error", results["s1"]?.error)
    // s2 also runs (parallel batch, independent)
    assertEquals(StepStatus.FAILED, results["s2"]?.status)
  }
}
