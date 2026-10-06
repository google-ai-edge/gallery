package com.google.ai.edge.gallery.desktop

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Developer smoke test (no UI, no installer): runs the exact same code path the AI Chat screen uses
 * to initialize a model (ModelManagerViewModel.initializeModel -> LlmChatTask -> agent executor ->
 * LlmChatModelHelper), then sends one prompt through the same runtime helper.
 *
 * Run with: gradlew :desktop:runInitSmokeTest [-PsmokeModel=Gemma-4-E2B-it] [-PsmokeTask=llm_chat]
 */
fun main(args: Array<String>) {
  Thread.setDefaultUncaughtExceptionHandler { t, e ->
    System.err.println("UNCAUGHT on ${t.name}: $e")
    e.printStackTrace()
  }
  val modelName = args.getOrNull(0) ?: "Gemma-4-E2B-it"
  val taskId = args.getOrNull(1) ?: "llm_chat"
  val vm = DesktopAppModule.modelManagerViewModel
  val ctx = DesktopAppModule.context
  val t0 = System.currentTimeMillis()
  fun ts() = "[+${System.currentTimeMillis() - t0} ms]"

  println("${ts()} SMOKE: loading allowlist")
  DesktopAppModule.initializeAllowlistIfMissing()
  vm.loadModelAllowlist()
  val deadline = System.currentTimeMillis() + 60_000
  while (vm.uiState.value.loadingModelAllowlist && System.currentTimeMillis() < deadline) {
    Thread.sleep(200)
  }
  val task = vm.uiState.value.tasks.firstOrNull { it.id == taskId }
  if (task == null) {
    println("SMOKE FAIL: task '$taskId' not found. Tasks: ${vm.uiState.value.tasks.map { it.id }}")
    exitProcess(2)
  }
  val model: Model? = task.models.firstOrNull { it.name == modelName }
  if (model == null) {
    println("SMOKE FAIL: model '$modelName' not in task. Models: ${task.models.map { it.name }}")
    exitProcess(2)
  }
  println("${ts()} SMOKE: download status = ${vm.uiState.value.modelDownloadStatus[model.name]?.status}")
  println("${ts()} SMOKE: model path = ${model.getPath(ctx)}")

  val initLatch = CountDownLatch(1)
  var initError: String? = null
  println("${ts()} SMOKE: calling ModelManagerViewModel.initializeModel (same as ChatView)")
  vm.initializeModel(
    context = ctx,
    task = task,
    model = model,
    onDone = { initLatch.countDown() },
    onError = { initError = it; initLatch.countDown() },
  )
  if (!initLatch.await(180, TimeUnit.SECONDS)) {
    println("${ts()} SMOKE FAIL: initialization did not finish within 180 s. status=${model.initStatusFlow.value}")
    exitProcess(3)
  }
  if (initError != null) {
    println("${ts()} SMOKE FAIL: initialization error: $initError")
    exitProcess(4)
  }
  println("${ts()} SMOKE: initialized. status=${model.initStatusFlow.value::class.simpleName}")

  val prompt = args.getOrNull(2) ?: "Reply with one short sentence: what is 2+2?"
  println("${ts()} SMOKE: prompt> $prompt")
  val doneLatch = CountDownLatch(1)
  val sb = StringBuilder()
  var inferError: String? = null
  model.runtimeHelper.runInference(
    model = model,
    input = prompt,
    resultListener = { partial, done, _ ->
      if (partial.isNotEmpty()) { print(partial); sb.append(partial) }
      if (done) doneLatch.countDown()
    },
    cleanUpListener = {},
    onError = { inferError = it; doneLatch.countDown() },
    images = emptyList(),
    audioClips = emptyList(),
    coroutineScope = null,
    extraContext = null,
    sessionId = null,
    messageIndex = null,
  )
  if (!doneLatch.await(180, TimeUnit.SECONDS)) {
    println("\n${ts()} SMOKE FAIL: inference timed out")
    exitProcess(5)
  }
  println()
  if (inferError != null || sb.isBlank()) {
    println("${ts()} SMOKE FAIL: inference error=$inferError text='${sb}'")
    exitProcess(6)
  }
  println("${ts()} SMOKE PASS: real response received (${sb.length} chars)")
  exitProcess(0)
}
