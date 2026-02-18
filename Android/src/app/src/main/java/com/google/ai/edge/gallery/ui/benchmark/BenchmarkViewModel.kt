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
package com.google.ai.edge.gallery.ui.benchmark

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkBasicInfo
import com.google.ai.edge.gallery.proto.LlmBenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkStats
import com.google.ai.edge.gallery.proto.ValueSeries
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGBenchmarkVM"

enum class Aggregation(val label: String) {
  AVG(label = "avg"),
  MEDIAN(label = "median"),
  MIN(label = "min"),
  MAX(label = "max"),
  // P25(label = "p25"),
  // P75(label = "p75"),
}

data class BenchmarkResultInfo(
  val id: String,
  val benchmarkResult: BenchmarkResult,
  val expanded: Boolean = false,
  val basicInfoExpanded: Boolean = true,
  val statsExpanded: Boolean = true,
  val aggregation: Aggregation = Aggregation.AVG,
)

data class BenchmarkUiState(
  val results: List<BenchmarkResultInfo> = listOf(),
  val baselineResult: BenchmarkResultInfo? = null,
  val showResultsViewer: Boolean = false,
  val running: Boolean = false,
  val totalRunCount: Int = 0,
  val completedRunCount: Int = 0,
)

@HiltViewModel
class BenchmarkViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  protected val _uiState = MutableStateFlow(BenchmarkUiState())
  val uiState = _uiState.asStateFlow()

  init {
    // Load results from storage.
    val storedResults = dataStoreRepository.getAllBenchmarkResults()
    Log.d(TAG, "Loaded ${storedResults.size} benchmark results")
    setBenchmarkResults(results = storedResults)
    collapseAll()
  }

  @OptIn(ExperimentalApi::class)
  fun runBenchmark(
    model: Model,
    accelerator: String,
    prefillTokens: Int,
    decodeTokens: Int,
    runCount: Int,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setRunning(running = true)
      setRunProgress(completedRunCount = 0)
      setTotalRunCount(totalRunCount = runCount)
      setShowResultsViewer(showResultsViewer = true)

      val parts: List<String> =
        listOf(
          "- model: ${model.name}",
          "- accelerator: $accelerator",
          "- prefill tokens: $prefillTokens",
          "- decode tokens: $decodeTokens",
          "- runs: $runCount",
        )
      Log.d(TAG, "Running benchmark: ${parts.joinToString("\n")}")

      // TODO: handle error.
      val startMs = System.currentTimeMillis()
      val prefillSpeeds = mutableListOf<Double>()
      val decodeSpeeds = mutableListOf<Double>()
      val timesToFirstToken = mutableListOf<Double>()
      var firstInitTime = 0.0
      val nonFirstInitTimes = mutableListOf<Double>()
      // Create a temporary cache dir to run benchmark in.
      val timestamp = System.currentTimeMillis()
      var needCleanUpCacheDir = true
      val benchmarkCacheDir = File(appContext.cacheDir, "benchmark_$timestamp")
      var cacheDirPath = benchmarkCacheDir.absolutePath
      if (!benchmarkCacheDir.mkdirs()) {
        Log.e(TAG, "Failed to create benchmark cache directory: ${benchmarkCacheDir.absolutePath}")
        cacheDirPath = appContext.cacheDir.absolutePath
        needCleanUpCacheDir = false
      }
      Log.d(TAG, "Using benchmark cache dir: $cacheDirPath")
      for (i in 0 until runCount) {
        Log.d(TAG, "Start running #$i...")
        val backend: Backend =
          when (accelerator.lowercase()) {
            "cpu" -> Backend.CPU
            "gpu" -> Backend.GPU
            "npu" -> Backend.NPU
            else -> Backend.CPU
          }
        val benchmarkInfo =
          benchmark(
            modelPath = model.getPath(context = appContext),
            backend = backend,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens,
            cacheDir = cacheDirPath,
          )
        Log.d(TAG, "Done #$i")

        val initTimeMs = benchmarkInfo.initTimeInSecond * 1000.0
        if (i == 0) {
          firstInitTime = initTimeMs
        } else {
          nonFirstInitTimes.add(initTimeMs)
        }
        prefillSpeeds.add(benchmarkInfo.lastPrefillTokensPerSecond)
        decodeSpeeds.add(benchmarkInfo.lastDecodeTokensPerSecond)
        timesToFirstToken.add(benchmarkInfo.timeToFirstTokenInSecond)

        // Mark finish for this run.
        setRunProgress(completedRunCount = i + 1)
      }
      val endMs = System.currentTimeMillis()
      if (needCleanUpCacheDir) {
        benchmarkCacheDir.deleteRecursively()
        Log.d(TAG, "Cleaned up benchmark cache dir: ${benchmarkCacheDir.absolutePath}")
      }

      // Create and add benchmark result.
      val basicInfo =
        LlmBenchmarkBasicInfo.newBuilder()
          .setStartMs(startMs)
          .setEndMs(endMs)
          .setModelName(model.name)
          .setAccelerator(accelerator)
          .setPrefillTokens(prefillTokens)
          .setDecodeTokens(decodeTokens)
          .setNumberOfRuns(runCount)
          .setAppVersion(BuildConfig.VERSION_NAME)
          .build()
      val stats =
        LlmBenchmarkStats.newBuilder()
          .setPrefillSpeed(calculateValueSeries(prefillSpeeds))
          .setDecodeSpeed(calculateValueSeries(decodeSpeeds))
          .setTimeToFirstToken(calculateValueSeries(timesToFirstToken))
          .setFirstInitTimeMs(firstInitTime)
          .setNonFirstInitTimeMs(calculateValueSeries(nonFirstInitTimes))
          .build()

      val result =
        BenchmarkResult.newBuilder()
          .setLlmResult(
            LlmBenchmarkResult.newBuilder().setBaiscInfo(basicInfo).setStats(stats).build()
          )
          .build()
      val newId = addBenchmarkResult(result = result)
      collapseAll()
      setExpanded(id = newId, expanded = true)

      setRunning(running = false)
    }
  }

  fun setShowResultsViewer(showResultsViewer: Boolean) {
    _uiState.update { _uiState.value.copy(showResultsViewer = showResultsViewer) }
  }

  fun setRunning(running: Boolean) {
    _uiState.update { _uiState.value.copy(running = running) }
  }

  fun setTotalRunCount(totalRunCount: Int) {
    _uiState.update { _uiState.value.copy(totalRunCount = totalRunCount) }
  }

  fun setRunProgress(completedRunCount: Int) {
    _uiState.update { _uiState.value.copy(completedRunCount = completedRunCount) }
  }

  fun addBenchmarkResult(result: BenchmarkResult): String {
    val newResults = _uiState.value.results.toMutableList()
    // Add the new result to the beginning of the list.
    val newId = "${Random.nextDouble()}"
    newResults.add(
      0,
      BenchmarkResultInfo(
        benchmarkResult = result,
        id = newId,
        basicInfoExpanded = true,
        statsExpanded = true,
      ),
    )
    _uiState.update { _uiState.value.copy(results = newResults) }

    // Save to storage.
    dataStoreRepository.addBenchmarkResult(result)

    return newId
  }

  fun setBenchmarkResults(results: List<BenchmarkResult>) {
    _uiState.update {
      _uiState.value.copy(
        results =
          results.map { result ->
            BenchmarkResultInfo(
              benchmarkResult = result,
              expanded = false,
              id = "${Random.nextDouble()}",
              basicInfoExpanded = false,
              statsExpanded = true,
            )
          }
      )
    }
  }

  fun deleteBenchmarkResult(id: String) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      val deletedResult = newResults.removeAt(index)
      _uiState.update { _uiState.value.copy(results = newResults) }
      if (deletedResult.id == uiState.value.baselineResult?.id) {
        _uiState.update { _uiState.value.copy(baselineResult = null) }
      }

      // Update storage.
      dataStoreRepository.deleteBenchmarkResult(index = index)
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setBaseline(id: String) {
    if (id == uiState.value.baselineResult?.id) {
      clearBaseline()
    } else {
      val result = _uiState.value.results.firstOrNull { it.id == id }
      if (result == null) {
        Log.w(TAG, "Benchmark result with id $id not found.")
        return
      }
      _uiState.update { _uiState.value.copy(baselineResult = result) }
    }
  }

  fun clearBaseline() {
    _uiState.update { _uiState.value.copy(baselineResult = null) }
  }

  fun setExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] =
        newResults[index].copy(
          expanded = expanded,
          basicInfoExpanded = expanded,
          statsExpanded = expanded,
        )
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setBasicInfoExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] = newResults[index].copy(basicInfoExpanded = expanded)
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setStatsExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] = newResults[index].copy(statsExpanded = expanded)
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun expandAll() {
    val newResults = _uiState.value.results.toMutableList()
    for (i in newResults.indices) {
      newResults[i] =
        newResults[i].copy(expanded = true, statsExpanded = true, basicInfoExpanded = true)
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  fun collapseAll() {
    val newResults = _uiState.value.results.toMutableList()
    for (i in newResults.indices) {
      newResults[i] =
        newResults[i].copy(expanded = false, statsExpanded = false, basicInfoExpanded = false)
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  fun setAggregation(id: String, aggregation: Aggregation) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index >= 0) {
      newResults[index] = newResults[index].copy(aggregation = aggregation)
      if (uiState.value.baselineResult?.id == newResults[index].id) {
        _uiState.update { _uiState.value.copy(baselineResult = newResults[index]) }
      }
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  private fun calculateValueSeries(values: List<Double>): ValueSeries {
    if (values.isEmpty()) {
      return ValueSeries.getDefaultInstance()
    }

    val sortedValues = values.sorted()
    val size = sortedValues.size

    val min = sortedValues.first()
    val max = sortedValues.last()
    val avg = values.average()

    // Helper function to get the value at a specific percentile (0.0 to 1.0)
    fun getPercentile(p: Double): Double {
      if (size == 1) return sortedValues[0]
      val index = p * (size - 1)
      val lower = floor(index).toInt()
      val upper = ceil(index).toInt()
      if (lower == upper) {
        return sortedValues[lower]
      }
      val weight = index - lower
      return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight
    }

    val median = getPercentile(0.5)
    val pct25 = getPercentile(0.25)
    val pct75 = getPercentile(0.75)

    return ValueSeries.newBuilder()
      .addAllValue(values)
      .setMin(min)
      .setMax(max)
      .setAvg(avg)
      .setMedium(median) // Proto field is named 'medium'
      .setPct25(pct25)
      .setPct75(pct75)
      .build()
  }
}
