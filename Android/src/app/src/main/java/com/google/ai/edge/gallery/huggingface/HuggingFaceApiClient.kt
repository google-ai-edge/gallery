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

package com.google.ai.edge.gallery.huggingface

import android.util.Log
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSiblingProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import com.google.ai.edge.gallery.proto.hfModelItemProto
import com.google.ai.edge.gallery.proto.hfSiblingProto
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "AGHfApiClient"
private const val HF_API_BASE_URL = "https://huggingface.co/api"
private const val USER_AGENT = "AIEdgeGallery/1.0 (Android)"

open class HuggingFaceApiClient
@Inject
constructor(@IoDispatcher private val ioDispatcher: CoroutineDispatcher) {
  /**
   * Fetches models from the Hugging Face API and applies official community promotion sorting.
   * Search fetching and model sorting are decoupled via [fetchModels] and [sortModels].
   */
  suspend fun searchModels(
    query: String = "",
    sort: HfSortOptionProto = HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS,
    limit: Int = 50,
    accessToken: String? = null,
  ): List<HfModelItemProto> {
    val rawModels = fetchModels(query = query, limit = limit, accessToken = accessToken)
    return sortModels(rawModels, sort)
  }

  /** Fetches matching catalog items from Hugging Face API without imposing a display sort order. */
  suspend fun fetchModels(
    query: String = "",
    limit: Int = 50,
    accessToken: String? = null,
  ): List<HfModelItemProto> =
    withContext(ioDispatcher) {
      val urlString = buildApiUrl(query = query, limit = limit)
      val responseText = executeGetRequest(urlString = urlString, accessToken = accessToken)

      if (responseText.isNullOrEmpty()) {
        return@withContext emptyList()
      }

      return@withContext parseModelListJson(responseText)
    }

  private fun parseModelListJson(responseText: String): List<HfModelItemProto> {
    val jsonElement =
      try {
        JsonParser.parseString(responseText)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse model list JSON", e)
        return emptyList()
      }
    if (!jsonElement.isJsonArray) {
      return emptyList()
    }
    val jsonArray = jsonElement.asJsonArray
    val models = jsonArray.mapNotNull { element ->
      if (element.isJsonObject) parseHfModelItemProto(element.asJsonObject) else null
    }
    return models.filter { it.hasCompatibleModelFiles() }
  }

  private fun buildApiUrl(query: String, limit: Int): String {
    val queryParams =
      mutableListOf(
        "filter" to "litert-lm",
        "limit" to limit.toString(),
        "expand" to "siblings",
        "expand" to "tags",
        "expand" to "likes",
        "expand" to "downloads",
        "expand" to "lastModified",
      )
    if (query.isNotBlank()) {
      queryParams.add("search" to URLEncoder.encode(query.trim(), "UTF-8"))
    }
    val queryString = queryParams.joinToString("&") { (key, value) -> "$key=$value" }
    return "$HF_API_BASE_URL/models?$queryString"
  }

  open suspend fun getModelDetails(
    modelId: String,
    accessToken: String? = null,
  ): HfModelItemProto? =
    withContext(ioDispatcher) {
      val urlString = "$HF_API_BASE_URL/models/$modelId?blobs=true"
      val responseText = executeGetRequest(urlString = urlString, accessToken = accessToken)

      if (responseText.isNullOrEmpty()) {
        return@withContext null
      }
      val jsonElement = JsonParser.parseString(responseText)
      if (jsonElement.isJsonObject) {
        return@withContext parseHfModelItemProto(jsonElement.asJsonObject)
      }
      return@withContext null
    }

  private fun executeGetRequest(urlString: String, accessToken: String? = null): String? {
    return try {
      Log.d(TAG, "Executing HF HTTP GET request: $urlString")
      val connection = URL(urlString).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.setRequestProperty("User-Agent", USER_AGENT)
      if (!accessToken.isNullOrEmpty()) {
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
      }
      connection.connect()

      val responseCode = connection.responseCode
      if (responseCode == HttpURLConnection.HTTP_OK) {
        connection.inputStream.bufferedReader().use { it.readText() }
      } else {
        Log.e(TAG, "HF API returned HTTP $responseCode for URL: $urlString")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed HTTP GET request to HF API for URL: $urlString", e)
      null
    }
  }

  private fun parseHfModelItemProto(jsonObj: JsonObject): HfModelItemProto = hfModelItemProto {
    jsonObj.getOrNull("id")?.let { id = it.asString }
    jsonObj.getOrNull("author")?.let { author = it.asString }
    jsonObj.getOrNull("description")?.let { description = it.asString }
    jsonObj.getOrNull("downloads")?.let { downloads = it.asLong }
    jsonObj.getOrNull("likes")?.let { likes = it.asLong }
    jsonObj.getOrNull("lastModified")?.let { lastModified = it.asString }

    jsonObj
      .getAsJsonArray("tags")
      ?.filterNot { it.isJsonNull }
      ?.forEach { tag -> tags += tag.asString }

    // For each file (sibling) in the model, add a sibling proto to the model proto.
    jsonObj
      .getAsJsonArray("siblings")
      ?.filter { it.isJsonObject }
      ?.mapNotNull { parseHfSiblingProto(it.asJsonObject) }
      ?.forEach { sibling -> siblings += sibling }
  }

  private fun parseHfSiblingProto(sibObj: JsonObject): HfSiblingProto? {
    val filename = sibObj.getOrNull("rfilename")?.asString
    if (filename.isNullOrEmpty()) return null

    return hfSiblingProto {
      rfilename = filename
      parseSiblingFileSize(sibObj)?.let { size = it }
    }
  }

  private fun parseSiblingFileSize(sibObj: JsonObject): Long? {
    sibObj.getOrNull("size")?.asLong?.let {
      return it
    }

    val lfsObj = sibObj.getOrNull("lfs")?.takeIf { it.isJsonObject }?.asJsonObject
    return lfsObj?.getOrNull("size")?.asLong
  }

  private fun JsonObject.getOrNull(member: String) =
    if (has(member) && !get(member).isJsonNull) get(member) else null
}
