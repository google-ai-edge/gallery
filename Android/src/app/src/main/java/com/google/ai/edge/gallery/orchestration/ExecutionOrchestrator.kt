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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGExecutionOrchestrator"

/**
 * Module 2: Execution Orchestrator.
 *
 * Takes an [ExecutionPlan] (already batched by the [Planner]) and executes each batch. Tool-only
 * steps within a batch run in parallel; LLM-needing steps are serialized through a [Mutex] because
 * the LiteRT-LM Conversation is single-threaded.
 */
class ExecutionOrchestrator(
  private val llmProvider: LlmInferenceProvider,
  private val toolExecutor: ToolExecutor,
) {
  private val cancelled = AtomicBoolean(false)
  private val llmMutex = Mutex()

  /**
   * Execute a full plan organized into sequential batches.
   *
   * @param plan The execution plan.
   * @param batches Pre-computed batches from [Planner.getExecutionBatches].
   * @param onStepUpdate Callback invoked when a step's status changes.
   * @return Map of step ID to result.
   */
  suspend fun executePlan(
    plan: ExecutionPlan,
    batches: List<List<PlanStep>>,
    onStepUpdate: (StepResult) -> Unit,
  ): Map<String, StepResult> {
    cancelled.set(false)
    val allResults = mutableMapOf<String, StepResult>()

    for ((batchIndex, batch) in batches.withIndex()) {
      if (cancelled.get()) {
        Log.d(TAG, "Cancelled before batch $batchIndex")
        // Mark remaining steps as skipped.
        for (step in batch) {
          val result = StepResult(stepId = step.id, status = StepStatus.SKIPPED)
          allResults[step.id] = result
          onStepUpdate(result)
        }
        continue
      }

      Log.d(TAG, "Executing batch $batchIndex with ${batch.size} steps")
      val batchResults = executeBatch(batch, allResults, onStepUpdate)
      allResults.putAll(batchResults)
    }

    return allResults
  }

  /** Cancel the current execution. Checked between steps and batches. */
  fun cancel() {
    cancelled.set(true)
    llmProvider.cancel()
  }

  /**
   * Execute a single batch. Tool-only steps run in parallel, LLM steps are serialized.
   */
  private suspend fun executeBatch(
    batch: List<PlanStep>,
    previousResults: Map<String, StepResult>,
    onStepUpdate: (StepResult) -> Unit,
  ): Map<String, StepResult> = coroutineScope {
    val deferreds =
      batch.map { step ->
        async {
          if (cancelled.get()) {
            val result = StepResult(stepId = step.id, status = StepStatus.SKIPPED)
            onStepUpdate(result)
            return@async step.id to result
          }

          // Notify: step is running.
          onStepUpdate(StepResult(stepId = step.id, status = StepStatus.RUNNING))

          val result = executeStep(step, previousResults)
          onStepUpdate(result)
          step.id to result
        }
      }

    deferreds.awaitAll().toMap()
  }

  /** Execute a single step, choosing the right execution path based on toolName. */
  private suspend fun executeStep(
    step: PlanStep,
    previousResults: Map<String, StepResult>,
  ): StepResult {
    val startTime = System.currentTimeMillis()

    return try {
      val result =
        when (step.toolName) {
          "loadSkill",
          "runJs",
          "runIntent" -> executeToolStep(step)
          null -> executeLlmStep(step, previousResults)
          else -> {
            Log.w(TAG, "Unknown tool: ${step.toolName}, treating as LLM step")
            executeLlmStep(step, previousResults)
          }
        }

      result.copy(durationMs = System.currentTimeMillis() - startTime)
    } catch (e: Exception) {
      Log.e(TAG, "Step ${step.id} failed with exception", e)
      StepResult(
        stepId = step.id,
        status = StepStatus.FAILED,
        error = e.message ?: "Unknown error",
        durationMs = System.currentTimeMillis() - startTime,
      )
    }
  }

  /** Execute a tool-based step. These can run in parallel. */
  private suspend fun executeToolStep(step: PlanStep): StepResult {
    Log.d(TAG, "Executing tool step: ${step.id} (${step.toolName})")

    val args = step.toolArgs.toMutableMap()
    // Ensure skillName is in args if the tool needs it.
    if (step.skillName != null && !args.containsKey("skillName")) {
      args["skillName"] = step.skillName
    }

    val toolResult = toolExecutor.executeTool(step.toolName!!, args)

    return StepResult(
      stepId = step.id,
      status = if (toolResult.success) StepStatus.COMPLETED else StepStatus.FAILED,
      output = toolResult.output,
      error = toolResult.error,
    )
  }

  /**
   * Execute an LLM-based step. Serialized through [llmMutex] because the underlying Conversation
   * is single-threaded.
   */
  private suspend fun executeLlmStep(
    step: PlanStep,
    previousResults: Map<String, StepResult>,
  ): StepResult {
    Log.d(TAG, "Executing LLM step: ${step.id}")

    return llmMutex.withLock {
      // Build context from dependency results.
      val contextParts = mutableListOf<String>()
      for (depId in step.dependsOn) {
        val depResult = previousResults[depId]
        if (depResult != null && depResult.status == StepStatus.COMPLETED) {
          contextParts.add("Result from $depId: ${depResult.output.take(500)}")
        }
      }

      val prompt = buildString {
        if (contextParts.isNotEmpty()) {
          append("Context from previous steps:\n")
          append(contextParts.joinToString("\n"))
          append("\n\n")
        }
        append("Task: ${step.description}")
      }

      val response = llmProvider.generateResponse(prompt)

      StepResult(
        stepId = step.id,
        status = StepStatus.COMPLETED,
        output = response,
      )
    }
  }
}
