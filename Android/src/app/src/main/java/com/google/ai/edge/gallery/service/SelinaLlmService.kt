/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "SelinaLlmService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "selina_llm_service_channel"

/**
 * Foreground service that keeps the Selina LLM (Gemma3-1B-IT) loaded in memory at all times.
 * This enables instant responses without loading delays.
 */
class SelinaLlmService : Service() {

    private val binder = SelinaLlmBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var modelInstance: LlmModelInstance? = null
    private var isModelLoaded = false
    private var loadingError: String? = null

    inner class SelinaLlmBinder : Binder() {
        fun getService(): SelinaLlmService = this@SelinaLlmService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        // Load the model
        loadModel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY // Restart service if killed by system
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        cleanUpModel()
    }

    /**
     * Loads Gemma3-1B-IT model optimized for low-power background operation.
     */
    private fun loadModel() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Loading Gemma3-1B-IT model...")
                updateNotification("Loading AI model...")

                // Create a minimal Model object for Gemma3-1B-IT
                // In production, this should come from your model repository
                val modelPath = "${applicationContext.filesDir}/models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task"

                // Check if model file exists
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    throw Exception("Model file not found at: $modelPath. Please download Gemma3-1B-IT first from the Model Manager.")
                }

                // Build LLM Inference options with CPU for low power consumption
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024) // Lower for background use
                    .setPreferredBackend(LlmInference.Backend.CPU) // CPU = lower power than GPU
                    .setMaxNumImages(0) // Disable image support for background service
                    .build()

                // Create LLM Inference instance
                val llmInference = LlmInference.createFromOptions(applicationContext, options)

                // Create inference session with default parameters
                val session = LlmInferenceSession.createFromOptions(
                    llmInference,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(DEFAULT_TOPK)
                        .setTopP(DEFAULT_TOPP)
                        .setTemperature(DEFAULT_TEMPERATURE)
                        .setGraphOptions(
                            GraphOptions.builder()
                                .setEnableVisionModality(false)
                                .build()
                        )
                        .build()
                )

                modelInstance = LlmModelInstance(engine = llmInference, session = session)
                isModelLoaded = true
                loadingError = null

                Log.d(TAG, "Model loaded successfully")
                updateNotification("Selina is ready")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                loadingError = e.message ?: "Unknown error"
                isModelLoaded = false
                updateNotification("Failed to load model")
            }
        }
    }

    /**
     * Runs inference on the loaded model.
     *
     * @param input The user's text prompt
     * @param onResult Callback for partial results (streaming)
     * @param onComplete Callback when inference is complete
     */
    fun runInference(
        input: String,
        onResult: (partialResult: String, done: Boolean) -> Unit,
        onComplete: () -> Unit
    ) {
        if (!isModelLoaded) {
            onResult("Error: Model not loaded. ${loadingError ?: ""}", true)
            onComplete()
            return
        }

        val instance = modelInstance ?: run {
            onResult("Error: Model instance is null", true)
            onComplete()
            return
        }

        serviceScope.launch {
            try {
                val session = instance.session

                // Add query
                if (input.trim().isNotEmpty()) {
                    session.addQueryChunk(input)
                }

                // Run inference with streaming
                session.generateResponseAsync { partialResult, done ->
                    onResult(partialResult, done)
                    if (done) {
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                onResult("Error: ${e.message}", true)
                onComplete()
            }
        }
    }

    /**
     * Resets the conversation session without reloading the model.
     */
    fun resetSession() {
        val instance = modelInstance ?: return

        try {
            Log.d(TAG, "Resetting session")
            val oldSession = instance.session
            oldSession.close()

            // Create new session
            val newSession = LlmInferenceSession.createFromOptions(
                instance.engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(DEFAULT_TOPK)
                    .setTopP(DEFAULT_TOPP)
                    .setTemperature(DEFAULT_TEMPERATURE)
                    .setGraphOptions(
                        GraphOptions.builder()
                            .setEnableVisionModality(false)
                            .build()
                    )
                    .build()
            )

            instance.session = newSession
            Log.d(TAG, "Session reset complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset session", e)
        }
    }

    /**
     * Returns true if the model is loaded and ready for inference.
     */
    fun isReady(): Boolean = isModelLoaded

    /**
     * Returns the loading error message if any.
     */
    fun getError(): String? = loadingError

    private fun cleanUpModel() {
        modelInstance?.let { instance ->
            try {
                instance.session.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close session", e)
            }

            try {
                instance.engine.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close engine", e)
            }
        }

        modelInstance = null
        isModelLoaded = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Selina Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Selina AI assistant running in the background"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Selina Assistant")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
    }

    companion object {
        /**
         * Starts the Selina LLM service.
         */
        fun start(context: Context) {
            val intent = Intent(context, SelinaLlmService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Stops the Selina LLM service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SelinaLlmService::class.java)
            context.stopService(intent)
        }
    }
}
