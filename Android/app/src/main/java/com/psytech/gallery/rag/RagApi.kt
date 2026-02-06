package com.psytech.gallery.rag

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import okhttp3.OkHttpClient

// ---- Request/Response models ----
data class RagTextReq(val collection: String, val text: String, val top_k: Int = 5)
data class RagVecReq(val collection: String, val vector: List<Float>, val top_k: Int = 5)
data class RagHit(val payload: Map<String, Any>?, val score: Double)
data class RagResp(val hits: List<RagHit>)

// ---- Retrofit API ----
interface RagApi {
  @POST("search_text")
  suspend fun searchText(@Body body: RagTextReq, @Header("api-key") apiKey: String? = null): RagResp

  @POST("search_vector")
  suspend fun searchVector(@Body body: RagVecReq, @Header("api-key") apiKey: String? = null): RagResp
}

// ---- Builder ----
object RagClient {
  fun create(baseUrl: String): RagApi =
    Retrofit.Builder()
      .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
      .addConverterFactory(MoshiConverterFactory.create())
      .client(OkHttpClient())
      .build()
      .create(RagApi::class.java)
}

