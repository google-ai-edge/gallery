package com.google.aiedge.edge_gallery_flutter

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NativeBridgeHandler(
    private val context: Context,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val runtimeCoordinator by lazy { NativeRuntimeCoordinator.getInstance(context) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventSink: EventChannel.EventSink? = null

    companion object {
        private const val CHANNEL_NAME = "edge_gallery_flutter/native_bridge"
        private const val EVENT_CHANNEL_NAME = "edge_gallery_flutter/native_events"
        private const val BRIDGE_VERSION = "0.1.0"
    }

    fun attach(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).setMethodCallHandler(this)
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL_NAME,
        ).setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getNativeBootstrap" -> result.success(buildBootstrapPayload())
                "getNativeCapabilities" -> result.success(buildCapabilityPayload())
                "getServerStatus" -> result.success(runtimeCoordinator.getServerStatus())
                "getModelInventory" -> result.success(runtimeCoordinator.getModelInventory())
                "startNativeServer" -> {
                    val useTunnel = call.argument<Boolean>("useTunnel") ?: true
                    val tunnelProvider = call.argument<String>("tunnelProvider") ?: "cloudflare"
                    runtimeCoordinator.startServer(useTunnel, tunnelProvider)
                    result.success(true)
                }
                "stopNativeServer" -> {
                    runtimeCoordinator.stopServer()
                    result.success(true)
                }
                "downloadModel" -> {
                    executeAsync(result) {
                        runtimeCoordinator.downloadModel(
                            call.argument<String>("modelName")
                                ?: error("Missing modelName"),
                        )
                        true
                    }
                }
                "cancelDownloadModel" -> {
                    runtimeCoordinator.cancelDownloadModel(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "deleteModel" -> {
                    runtimeCoordinator.deleteModel(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "loadModel" -> {
                    runtimeCoordinator.loadModel(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "unloadModel" -> {
                    runtimeCoordinator.unloadModel(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "resetConversation" -> {
                    runtimeCoordinator.resetConversation(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "stopChatGeneration" -> {
                    runtimeCoordinator.stopChatGeneration(
                        call.argument<String>("modelName")
                            ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "sendChatMessage" -> {
                    runtimeCoordinator.sendChatMessage(
                        modelName = call.argument<String>("modelName") ?: error("Missing modelName"),
                        prompt = call.argument<String>("prompt") ?: "",
                        documentContext = call.argument<String>("documentContext"),
                        imagePaths = call.argument<List<String>>("imagePaths") ?: emptyList(),
                        audioPaths = call.argument<List<String>>("audioPaths") ?: emptyList(),
                        systemPrompt = call.argument<String>("systemPrompt"),
                        temperature = call.argument<Double>("temperature"),
                        maxTokens = call.argument<Int>("maxTokens"),
                    )
                    result.success(true)
                }
                "startApiChatCompletion" -> {
                    runtimeCoordinator.startApiChatCompletion(
                        requestId = call.argument<String>("requestId") ?: error("Missing requestId"),
                        modelName = call.argument<String>("modelName") ?: error("Missing modelName"),
                        prompt = call.argument<String>("prompt") ?: "",
                        temperature = call.argument<Double>("temperature"),
                        topP = call.argument<Double>("topP"),
                        topK = call.argument<Int>("topK"),
                        maxTokens = call.argument<Int>("maxTokens"),
                    )
                    result.success(true)
                }
                "stopApiChatCompletion" -> {
                    runtimeCoordinator.stopChatGeneration(
                        call.argument<String>("modelName") ?: error("Missing modelName"),
                    )
                    result.success(true)
                }
                "saveAccessToken" -> {
                    runtimeCoordinator.saveManualAccessToken(
                        call.argument<String>("accessToken") ?: error("Missing accessToken"),
                    )
                    result.success(true)
                }
                "clearAccessToken" -> {
                    runtimeCoordinator.clearAccessToken()
                    result.success(true)
                }
                "saveCloudflareTunnelConfig" -> {
                    runtimeCoordinator.saveCloudflareTunnelConfig(
                        tunnelToken = call.argument<String>("tunnelToken") ?: "",
                        publicUrl = call.argument<String>("publicUrl") ?: "",
                    )
                    result.success(true)
                }
                "saveNgrokConfig" -> {
                    runtimeCoordinator.saveNgrokConfig(
                        authToken = call.argument<String>("authToken") ?: "",
                        domain = call.argument<String>("domain") ?: "",
                    )
                    result.success(true)
                }
                "importLocalModel" -> {
                    runtimeCoordinator.importLocalModel(
                        filePath = call.argument<String>("filePath") ?: error("Missing filePath"),
                        fileName = call.argument<String>("fileName") ?: error("Missing fileName"),
                        defaultMaxTokens = (call.argument<Int>("defaultMaxTokens") ?: 2048),
                        defaultTopK = (call.argument<Int>("defaultTopK") ?: 40),
                        defaultTopP = (call.argument<Double>("defaultTopP") ?: 0.95),
                        defaultTemperature = (call.argument<Double>("defaultTemperature") ?: 0.8),
                        supportImage = call.argument<Boolean>("supportImage") ?: false,
                        supportAudio = call.argument<Boolean>("supportAudio") ?: false,
                        supportTinyGarden = call.argument<Boolean>("supportTinyGarden") ?: false,
                        supportMobileActions = call.argument<Boolean>("supportMobileActions") ?: false,
                        supportThinking = call.argument<Boolean>("supportThinking") ?: false,
                        accelerators = call.argument<List<String>>("accelerators") ?: emptyList(),
                    )
                    result.success(true)
                }
                "registerCustomRemoteModel" -> {
                    runtimeCoordinator.registerCustomRemoteModel(
                        name = call.argument<String>("name") ?: error("Missing name"),
                        url = call.argument<String>("url") ?: error("Missing url"),
                        fileName = call.argument<String>("fileName") ?: error("Missing fileName"),
                        sizeInBytes = call.argument<Long>("sizeInBytes") ?: 0L,
                        minRamGb = call.argument<Int>("minRamGb"),
                        defaultMaxTokens = call.argument<Int>("defaultMaxTokens") ?: 2048,
                        defaultTopK = call.argument<Int>("defaultTopK") ?: 40,
                        defaultTopP = call.argument<Double>("defaultTopP") ?: 0.95,
                        defaultTemperature = call.argument<Double>("defaultTemperature") ?: 0.8,
                        supportImage = call.argument<Boolean>("supportImage") ?: false,
                        supportAudio = call.argument<Boolean>("supportAudio") ?: false,
                        supportTinyGarden = call.argument<Boolean>("supportTinyGarden") ?: false,
                        supportMobileActions = call.argument<Boolean>("supportMobileActions") ?: false,
                        supportThinking = call.argument<Boolean>("supportThinking") ?: false,
                        accelerators = call.argument<List<String>>("accelerators") ?: emptyList(),
                    )
                    result.success(true)
                }
                "pingNativeRuntime" -> result.success(
                    "Native runtime bridge is alive on ${Build.MANUFACTURER} ${Build.MODEL}.",
                )
                else -> result.notImplemented()
            }
        } catch (error: Throwable) {
            result.error("native_bridge_error", error.message, null)
        }
    }

    private fun buildBootstrapPayload(): Map<String, Any> {
        return mapOf(
            "platform" to "Android",
            "release" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "appVersion" to appVersionName(),
            "bridgeVersion" to BRIDGE_VERSION,
            "abis" to Build.SUPPORTED_ABIS.toList(),
        )
    }

    private fun buildCapabilityPayload(): Map<String, Any> {
        val packageManager = context.packageManager

        return mapOf(
            "camera" to packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            "microphone" to packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            "gpuAcceleration" to true,
            "nnapi" to packageManager.hasSystemFeature("android.hardware.neuralnetworks"),
            "foregroundService" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O),
            "localServerHooks" to true,
            "notes" to "This bridge exposes native runtime, model lifecycle, local server, and chat hooks to Flutter.",
        )
    }

    private fun appVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        runtimeCoordinator.bindEventEmitter { payload ->
            mainHandler.post { eventSink?.success(payload) }
        }
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        runtimeCoordinator.bindEventEmitter(null)
    }

    private fun executeAsync(
        result: MethodChannel.Result,
        action: () -> Any?,
    ) {
        backgroundScope.launch {
            try {
                val value = action()
                mainHandler.post { result.success(value) }
            } catch (error: Throwable) {
                mainHandler.post {
                    result.error("native_bridge_error", error.message, null)
                }
            }
        }
    }
}
