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

package com.google.ai.edge.gallery.data

import com.google.gson.annotations.SerializedName
import javax.inject.Qualifier

/** Hilt qualifier annotation for feedback API key binding. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class FeedbackApiKey

/** Interface to fetch OAuth Bearer credentials on-device. */
interface AuthTokenProvider {
  suspend fun getAuthToken(scope: String): String?
}

/** Enum matching Feedback Oneplatform MicrofeedbackScore values for lightweight sentiment. */
enum class MicrofeedbackScore {
  @SerializedName("SCORE_UNSPECIFIED") SCORE_UNSPECIFIED,
  @SerializedName("SCORE0") SCORE0,
  @SerializedName("SCORE1") SCORE1,
  @SerializedName("SCORE2") SCORE2,
  @SerializedName("SCORE3") SCORE3,
  @SerializedName("SCORE4") SCORE4,
  @SerializedName("SCORE5") SCORE5,
}

/** A key-value pair for Product Specific Data (PSD) metadata attachment. */
data class ModelFeedbackPsdData(
  @SerializedName("key") val key: String,
  @SerializedName("value") val value: String,
)

/** Product metadata and environment info where the feedback was collected. */
data class ModelFeedbackProductInfo(
  @SerializedName("ui_language") val uiLanguage: String = "en-US",
  @SerializedName("product_version") val productVersion: String,
  @SerializedName("product_specific_data") val productSpecificData: List<ModelFeedbackPsdData>,
)

/** Core user entry details, including comment text and lightweight sentiment scores. */
data class ModelFeedbackDataPayload(
  @SerializedName("description") val description: String,
  @SerializedName("microfeedback_score") val microfeedbackScore: MicrofeedbackScore,
)

/** DTO request body for the Feedback Oneplatform SubmitFeedback RPC public endpoint. */
data class ModelFeedbackRequest(
  @SerializedName("product_id") val productId: Int = 5372309,
  @SerializedName("bucket_id") val bucketId: String = "android-agent-chat-feedback",
  @SerializedName("product_info") val productInfo: ModelFeedbackProductInfo,
  @SerializedName("feedback_data") val feedbackData: ModelFeedbackDataPayload,
)
