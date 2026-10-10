package com.google.ai.edge.gallery.debugbench

import android.app.Activity
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.services.semanticretrieval.UniversalOnDeviceEmbedder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only measurement: embeds every image of `dir` (long side `side` px) with each vision
 * token budget and the texts of `dir/texts.txt` (raw and with the search-query prompt), then
 * writes vectors and timings to `files/bench.json`.
 *
 * adb shell am start -n de.morgenschiss.gallery.dev/com.google.ai.edge.gallery.debugbench.EmbedBenchActivity \
 *   --es dir /sdcard/Download/embedbench --es budgets 70,280,560 --es accel GPU --ei side 1024
 */
class EmbedBenchActivity : Activity() {
  private lateinit var status: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    status = TextView(this).apply { textSize = 16f; setPadding(32, 96, 32, 32) }
    setContentView(status)
    val dir = File(intent.getStringExtra("dir") ?: "/sdcard/Download/embedbench")
    val budgets = (intent.getStringExtra("budgets") ?: "70").split(",").mapNotNull { it.trim().toIntOrNull() }
    val accel = Accelerator.fromLabel(intent.getStringExtra("accel") ?: "GPU") ?: Accelerator.GPU
    val side = intent.getIntExtra("side", 1024)
    val model = intent.getStringExtra("model") ?: File(filesDir, "bench/embeddinggemma-2-740m.litertlm").absolutePath
    CoroutineScope(Dispatchers.Default).launch {
      val out = runCatching { run(dir, budgets, accel, side, model) }.getOrElse { JSONObject().put("error", it.toString()) }
      File(filesDir, "bench/bench.json").writeText(out.toString())
      say("Fertig: " + out.optString("error", "ok"))
      Log.i("EmbedBench", "done")
    }
  }

  private suspend fun say(text: String) = withContext(Dispatchers.Main) { status.text = text }

  private suspend fun run(dir: File, budgets: List<Int>, accel: Accelerator, side: Int, model: String): JSONObject {
    val images = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic") }!!.sortedBy { it.name }
    val texts = File(filesDir, "bench/texts.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    val tgas = images.map { f ->
      val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
        val s = minOf(1f, side.toFloat() / maxOf(info.size.width, info.size.height))
        d.setTargetSize(maxOf(1, (info.size.width * s).toInt()), maxOf(1, (info.size.height * s).toInt()))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
      f.name to ImageUtils.encodeTga(bmp)
    }
    val mf = File(model)
    Log.i("EmbedBench", "model exists=${mf.exists()} canRead=${mf.canRead()} size=${mf.length()} images=${tgas.size} texts=${texts.size}")
    val result = JSONObject().put("accel", accel.label).put("side", side).put("model", model).put("modelReadable", mf.canRead())
    val runs = JSONArray()
    for ((bi, budget) in budgets.withIndex()) {
      say("Budget $budget: Modell laden …")
      val embedder = UniversalOnDeviceEmbedder(model, accel, applicationContext, 256, budget)
      val t0 = System.currentTimeMillis()
      embedder.initialize()
      val loadMs = System.currentTimeMillis() - t0
      val vecs = JSONObject()
      // the first image warms up the delegate; it is timed separately
      var first = 0L
      val t1 = System.currentTimeMillis()
      for ((i, pair) in tgas.withIndex()) {
        val s = System.currentTimeMillis()
        val v = embedder.generateImageEmbedding(pair.second)
        if (i == 0) Log.i("EmbedBench", "first vector: ${v?.size}")
        if (i == 0) first = System.currentTimeMillis() - s
        vecs.put(pair.first, JSONArray(v?.map { it.toDouble() } ?: emptyList<Double>()))
        if (i % 5 == 0) say("Budget $budget: Bild ${i + 1} von ${tgas.size}")
      }
      val total = System.currentTimeMillis() - t1
      val run = JSONObject().put("budget", budget).put("loadMs", loadMs).put("firstMs", first)
        .put("msPerImage", if (tgas.size > 1) (total - first) / (tgas.size - 1) else total).put("images", vecs)
      if (bi == 0) {
        val raw = JSONObject()
        val pre = JSONObject()
        val t2 = System.currentTimeMillis()
        for (t in texts) {
          raw.put(t, JSONArray(embedder.generateTextEmbedding(t)?.map { it.toDouble() } ?: emptyList<Double>()))
          pre.put(t, JSONArray(embedder.generateTextEmbedding("task: search result | query: $t")?.map { it.toDouble() } ?: emptyList<Double>()))
        }
        run.put("msPerText", if (texts.isNotEmpty()) (System.currentTimeMillis() - t2) / (2 * texts.size) else 0)
        result.put("textsRaw", raw).put("textsPrefixed", pre)
      }
      runs.put(run)
      embedder.close()
    }
    return result.put("runs", runs)
  }
}
