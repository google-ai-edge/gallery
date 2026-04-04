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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AGOrchestrationController"

/**
 * Public API for the orchestration module.
 *
 * Wires together [Planner], [ExecutionOrchestrator], and [SelfEvaluator] into a plan → execute →
 * evaluate loop. The app creates this controller, calls [run], observes [state], and optionally
 * calls [cancel].
 *
 * Usage:
 * ```
 * val controller = OrchestrationController(llmProvider, toolExecutor)
 * scope.launch { controller.run("Look up X and generate a QR code") }
 * controller.state.collect { state -> updateUI(state) }
 * ```
 */
class OrchestrationController(
  private val llmProvider: LlmInferenceProvider,
  private val toolExecutor: ToolExecutor,
  private val maxIterations: Int = 3,
) {
  private val planner = Planner()
  private val orchestrator = ExecutionOrchestrator(llmProvider, toolExecutor)
  private val evaluator = SelfEvaluator()

  private val _state = MutableStateFlow(OrchestrationState())
  val state: StateFlow<OrchestrationState> = _state.asStateFlow()

  private val cancelled = AtomicBoolean(false)

  /**
   * Main entry point: plan → execute → evaluate → loop.
   *
   * This is a suspending function that runs the full orchestration loop. It updates [state] at each
   * phase so the UI can react.
   */
  suspend fun run(userMessage: String) {
    cancelled.set(false)
    _state.value =
      OrchestrationState(
        status = OrchestrationStatus.PLANNING,
        maxIterations = maxIterations,
      )

    try {
      // ---- Phase 1: Plan ----
      Log.d(TAG, "Phase 1: Planning for: $userMessage")
      val skills = toolExecutor.getAvailableSkills()
      val planPrompt = planner.buildPlanningPrompt(userMessage, skills)
      val planResponse = llmProvider.generateResponse(planPrompt)
      val plan = planner.parsePlan(planResponse, userMessage)

      Log.d(TAG, "Plan created with ${plan.steps.size} steps")
      _state.value =
        _state.value.copy(
          status = OrchestrationStatus.EXECUTING,
          plan = plan,
          iteration = 1,
        )

      // ---- Phase 2+3: Execute → Evaluate loop ----
      var currentPlan = plan
      for (iteration in 1..maxIterations) {
        if (cancelled.get()) {
          Log.d(TAG, "Cancelled before iteration $iteration")
          _state.value = _state.value.copy(status = OrchestrationStatus.CANCELLED)
          return
        }

        Log.d(TAG, "Iteration $iteration: Executing plan")
        _state.value =
          _state.value.copy(
            status = OrchestrationStatus.EXECUTING,
            plan = currentPlan,
            iteration = iteration,
            stepResults = emptyMap(),
            evaluation = null,
          )

        // Execute.
        val batches = planner.getExecutionBatches(currentPlan)
        val results =
          orchestrator.executePlan(currentPlan, batches) { stepResult ->
            // Update state as each step completes.
            _state.value =
              _state.value.copy(
                stepResults = _state.value.stepResults + (stepResult.stepId to stepResult)
              )
          }

        _state.value = _state.value.copy(stepResults = results)

        if (cancelled.get()) {
          _state.value = _state.value.copy(status = OrchestrationStatus.CANCELLED)
          return
        }

        // Evaluate.
        Log.d(TAG, "Iteration $iteration: Evaluating results")
        _state.value = _state.value.copy(status = OrchestrationStatus.EVALUATING)

        val evalPrompt = evaluator.buildEvaluationPrompt(userMessage, currentPlan, results)
        val evalResponse = llmProvider.generateResponse(evalPrompt)
        val evaluation = evaluator.parseEvaluation(evalResponse)

        Log.d(TAG, "Evaluation: goalAchieved=${evaluation.goalAchieved}, shouldReplan=${evaluation.shouldReplan}")
        _state.value = _state.value.copy(evaluation = evaluation)

        if (evaluation.goalAchieved) {
          Log.d(TAG, "Goal achieved on iteration $iteration")
          val finalOutput = buildFinalOutput(currentPlan, results, evaluation)
          _state.value =
            _state.value.copy(
              status = OrchestrationStatus.COMPLETED,
              finalOutput = finalOutput,
            )
          return
        }

        if (!evaluation.shouldReplan || iteration == maxIterations) {
          Log.d(TAG, "Stopping: shouldReplan=${evaluation.shouldReplan}, iteration=$iteration/$maxIterations")
          val finalOutput = buildFinalOutput(currentPlan, results, evaluation)
          _state.value =
            _state.value.copy(
              status = OrchestrationStatus.COMPLETED,
              finalOutput = finalOutput,
            )
          return
        }

        // Replan.
        if (cancelled.get()) {
          _state.value = _state.value.copy(status = OrchestrationStatus.CANCELLED)
          return
        }

        Log.d(TAG, "Re-planning for iteration ${iteration + 1}")
        _state.value = _state.value.copy(status = OrchestrationStatus.REPLANNING)

        val replanPrompt =
          planner.buildReplanPrompt(userMessage, currentPlan, results, evaluation)
        val replanResponse = llmProvider.generateResponse(replanPrompt)
        currentPlan = planner.parsePlan(replanResponse, userMessage)

        Log.d(TAG, "Revised plan has ${currentPlan.steps.size} steps")
      }

      // Should not reach here, but just in case.
      _state.value = _state.value.copy(status = OrchestrationStatus.COMPLETED)
    } catch (e: Exception) {
      Log.e(TAG, "Orchestration failed", e)
      _state.value =
        _state.value.copy(
          status = OrchestrationStatus.ERROR,
          error = e.message ?: "Unknown error",
        )
    }
  }

  /** Cancel the orchestration loop. Safe to call from any thread. */
  fun cancel() {
    Log.d(TAG, "Cancel requested")
    cancelled.set(true)
    orchestrator.cancel()
  }

  /** Reset to idle state. Call before starting a new orchestration run. */
  fun reset() {
    cancelled.set(false)
    _state.value = OrchestrationState()
  }

  /** Build a summary of the final output from all step results. */
  private fun buildFinalOutput(
    plan: ExecutionPlan,
    results: Map<String, StepResult>,
    evaluation: EvaluationResult,
  ): String {
    val completedOutputs =
      plan.steps
        .mapNotNull { step ->
          val result = results[step.id]
          if (result != null && result.status == StepStatus.COMPLETED && result.output.isNotBlank()) {
            "${step.description}: ${result.output.take(500)}"
          } else {
            null
          }
        }

    return buildString {
      if (evaluation.goalAchieved) {
        append("Goal achieved.\n\n")
      } else {
        append("Partial results (goal not fully achieved).\n\n")
      }
      append("Results:\n")
      append(completedOutputs.joinToString("\n\n"))
      if (!evaluation.goalAchieved && evaluation.missingItems.isNotEmpty()) {
        append("\n\nMissing:\n")
        append(evaluation.missingItems.joinToString("\n") { "- $it" })
      }
    }
  }
}
