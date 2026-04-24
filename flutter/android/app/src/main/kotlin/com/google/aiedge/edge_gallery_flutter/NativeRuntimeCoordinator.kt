package com.google.aiedge.edge_gallery_flutter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.openai.OpenAiServerService
import com.google.ai.edge.gallery.openai.OpenAiServerState
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NativeRuntimeCoordinator private constructor(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            NativeRuntimeEntryPoint::class.java,
        )
    }

    @Volatile
    private var modelManagerViewModel: ModelManagerViewModel? = null

    @Volatile
    private var allowlistRequested = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeLoadedModelName = AtomicReference<String?>(null)
    private val activeSupportsImage = AtomicReference(false)
    private val activeSupportsAudio = AtomicReference(false)
    @Volatile
    private var downloadMonitorJob: Job? = null

    @Volatile
    private var currentOperationStatus: String = "idle"

    @Volatile
    private var currentOperationMessage: String = ""

    @Volatile
    private var eventEmitter: ((Map<String, Any?>) -> Unit)? = null

    private val customRemoteModelNames = linkedSetOf<String>()

    fun bindEventEmitter(emitter: ((Map<String, Any?>) -> Unit)?) {
        eventEmitter = emitter
    }

    fun getServerStatus(): Map<String, Any?> {
        val modelManager = getModelManager()
        val uiState = modelManager.uiState.value

        return mapOf(
            "isRunning" to OpenAiServerState.isRunning.value,
            "localUrl" to OpenAiServerState.localUrl.value,
            "publicUrl" to OpenAiServerState.publicUrl.value,
            "tunnelEnabled" to OpenAiServerState.isTunnelEnabled.value,
            "loadingModels" to uiState.loadingModelAllowlist,
            "modelError" to uiState.loadingModelAllowlistError,
            "activeModelName" to activeLoadedModelName.get(),
            "operationStatus" to currentOperationStatus,
            "operationMessage" to currentOperationMessage,
        )
    }

    fun getModelInventory(): Map<String, Any?> {
        val modelManager = getModelManager()
        val uiState = modelManager.uiState.value

        val models =
            modelManager.getAllModels().map { model ->
                val downloadStatus = uiState.modelDownloadStatus[model.name]
                val initializationStatus = uiState.modelInitializationStatus[model.name]
                val isActive = activeLoadedModelName.get() == model.name

                mapOf(
                    "name" to model.name,
                    "displayName" to model.displayName.ifEmpty { model.name },
                    "runtimeType" to model.runtimeType.name,
                    "downloadStatus" to (downloadStatus?.status?.name ?: ModelDownloadStatusType.NOT_DOWNLOADED.name),
                    "downloadedBytes" to (downloadStatus?.receivedBytes ?: 0L),
                    "totalBytes" to ((downloadStatus?.totalBytes ?: 0L).takeIf { it > 0 } ?: model.totalBytes),
                    "downloadError" to (downloadStatus?.errorMessage ?: ""),
                    "bytesPerSecond" to (downloadStatus?.bytesPerSecond ?: 0L),
                    "remainingMs" to (downloadStatus?.remainingMs ?: 0L),
                    "initializationStatus" to (initializationStatus?.status?.name ?: ModelInitializationStatusType.NOT_INITIALIZED.name),
                    "initializationError" to (initializationStatus?.error ?: ""),
                    "isInitialized" to (initializationStatus?.status == ModelInitializationStatusType.INITIALIZED),
                    "isInitializing" to (initializationStatus?.status == ModelInitializationStatusType.INITIALIZING),
                    "supportsImage" to model.llmSupportImage,
                    "supportsAudio" to model.llmSupportAudio,
                    "supportsThinking" to model.capabilities.contains(ModelCapability.LLM_THINKING),
                    "supportsMobileActions" to model.llmSupportMobileActions,
                    "imported" to model.imported,
                    "isCustomRemote" to customRemoteModelNames.contains(model.name),
                    "isActive" to isActive,
                    "sizeInBytes" to model.sizeInBytes,
                    "minRamGb" to model.minDeviceMemoryInGb,
                    "url" to model.url,
                )
            }

        return mapOf(
            "isLoading" to uiState.loadingModelAllowlist,
            "error" to uiState.loadingModelAllowlistError,
            "count" to models.size,
            "models" to models,
            "activeModelName" to activeLoadedModelName.get(),
            "operationStatus" to currentOperationStatus,
            "operationMessage" to currentOperationMessage,
        )
    }

    fun startServer(useTunnel: Boolean) {
        val modelManager = getModelManager()
        OpenAiServerState.modelManagerViewModel = modelManager
        OpenAiServerState.persistTunnelEnabled(applicationContext, useTunnel)
        OpenAiServerService.startService(applicationContext, useTunnel)
    }

    fun stopServer() {
        OpenAiServerService.stopService(applicationContext)
    }

    fun downloadModel(modelName: String) {
        val modelManager = getModelManager()
        val model = requireModel(modelName)
        val task = findPreferredTask(modelManager, model)
        val tokenData = modelManager.getTokenStatusAndData().data
        val accessToken = tokenData?.accessToken?.takeIf { it.isNotBlank() }
        if (accessToken != null) {
            model.accessToken = accessToken
        }
        val responseCode =
            if (model.url.isNotBlank()) {
                modelManager.getModelUrlResponse(model, accessToken)
            } else {
                200
            }
        if (responseCode == 401 || responseCode == 403) {
            val authConfigured =
                !ProjectConfig.clientId.startsWith("REPLACE_WITH_") &&
                    !ProjectConfig.redirectUri.startsWith("REPLACE_WITH_")
            throw IllegalStateException(
                if (authConfigured) {
                    "This model download is gated and needs authorization. Current response: HTTP $responseCode."
                } else {
                    "This model download is gated, and Hugging Face authorization is not configured yet in ProjectConfig. Current response: HTTP $responseCode."
                },
            )
        }
        if (responseCode >= 400 || responseCode < 0) {
            throw IllegalStateException(
                "Model download could not start. Remote server responded with HTTP $responseCode.",
            )
        }
        mainScope.launch {
            modelManager.downloadModel(task = task, model = model)
            emitEvent("model_download_requested", mapOf("modelName" to modelName))
            startDownloadProgressMonitor(modelName)
        }
    }

    fun saveManualAccessToken(accessToken: String) {
        val refreshToken = "manual-token"
        val expiresAt = System.currentTimeMillis() + 3650L * 24L * 60L * 60L * 1000L
        getModelManager().saveAccessToken(
            accessToken = accessToken.trim(),
            refreshToken = refreshToken,
            expiresAt = expiresAt,
        )
    }

    fun clearAccessToken() {
        getModelManager().clearAccessToken()
    }

    fun cancelDownloadModel(modelName: String) {
        val modelManager = getModelManager()
        val model = requireModel(modelName)
        stopDownloadProgressMonitor()
        modelManager.cancelDownloadModel(model)
        emitEvent("model_download_cancelled", mapOf("modelName" to modelName))
    }

    fun deleteModel(modelName: String) {
        val modelManager = getModelManager()
        val model = requireModel(modelName)
        stopDownloadProgressMonitor()
        if (activeLoadedModelName.get() == model.name) {
            activeLoadedModelName.set(null)
        }
        modelManager.deleteModel(model)
        emitEvent("model_deleted", mapOf("modelName" to modelName))
    }

    fun loadModel(modelName: String) {
        val modelManager = getModelManager()
        val targetModel = requireModel(modelName)
        val targetTask = findPreferredTask(modelManager, targetModel)
        scope.launch {
            val initializedOthers =
                modelManager.getAllModels().filter {
                    it.name != targetModel.name &&
                        modelManager.uiState.value.modelInitializationStatus[it.name]?.status == ModelInitializationStatusType.INITIALIZED
                }

            if (initializedOthers.isNotEmpty()) {
                updateOperation(
                    status = "unloading_previous",
                    message = "Unloading ${initializedOthers.first().displayName.ifEmpty { initializedOthers.first().name }}...",
                    modelName = targetModel.name,
                )
                cleanupModelsSequentially(
                    modelManager = modelManager,
                    models = initializedOthers,
                ) {
                    activeLoadedModelName.set(null)
                    beginModelInitialization(modelManager, targetTask, targetModel)
                }
                return@launch
            }

            beginModelInitialization(modelManager, targetTask, targetModel)
        }
    }

    fun unloadModel(modelName: String) {
        val modelManager = getModelManager()
        val model = requireModel(modelName)
        val task = findPreferredTask(modelManager, model)
        updateOperation(
            status = "unloading",
            message = "Unloading ${model.displayName.ifEmpty { model.name }}...",
            modelName = model.name,
        )
        modelManager.cleanupModel(
            context = applicationContext,
            task = task,
            model = model,
            onDone = {
                if (activeLoadedModelName.get() == model.name) {
                    activeLoadedModelName.set(null)
                }
                activeSupportsImage.set(false)
                activeSupportsAudio.set(false)
                updateOperation(status = "idle", message = "", modelName = model.name)
                emitEvent("model_unloaded", mapOf("modelName" to model.name))
            },
        )
    }

    fun resetConversation(modelName: String) {
        val model = requireModel(modelName)
        model.runtimeHelper.resetConversation(
            model = model,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
        )
        emitEvent("conversation_reset", mapOf("modelName" to model.name))
    }

    fun stopChatGeneration(modelName: String) {
        val model = requireModel(modelName)
        model.runtimeHelper.stopResponse(model)
        emitEvent("chat_stopped", mapOf("modelName" to model.name))
    }

    fun sendChatMessage(
        modelName: String,
        prompt: String,
        documentContext: String?,
        imagePaths: List<String>,
        audioPaths: List<String>,
        systemPrompt: String?,
        temperature: Double?,
        maxTokens: Int?,
    ) {
        val model = requireModel(modelName)
        if (imagePaths.isNotEmpty() && !model.llmSupportImage) {
            throw IllegalStateException("The loaded model does not support image input.")
        }
        if (audioPaths.isNotEmpty() && !model.llmSupportAudio) {
            throw IllegalStateException("The loaded model does not support audio input.")
        }

        val fullPrompt =
            buildString {
                if (!systemPrompt.isNullOrBlank()) {
                    appendLine("System instruction:")
                    appendLine(systemPrompt.trim())
                    appendLine()
                }
                if (!documentContext.isNullOrBlank()) {
                    appendLine("Document context:")
                    appendLine(documentContext.trim())
                    appendLine()
                    appendLine("User request:")
                }
                append(prompt.trim())
            }
        if (temperature != null) {
            model.configValues =
                model.configValues + (ConfigKeys.TEMPERATURE.label to temperature.toFloat())
        }
        if (maxTokens != null && maxTokens > 0) {
            model.configValues =
                model.configValues + (ConfigKeys.MAX_TOKENS.label to maxTokens)
        }
        val images = imagePaths.mapNotNull(::decodeBitmap)
        val audioClips = audioPaths.mapNotNull(::readAudioBytes)
        if (imagePaths.isNotEmpty() && images.isEmpty()) {
            throw IllegalStateException("The selected image could not be prepared for inference.")
        }
        if (audioPaths.isNotEmpty() && audioClips.isEmpty()) {
            throw IllegalStateException("The selected audio clip could not be prepared for inference.")
        }

        emitEvent(
            "chat_started",
            mapOf(
                "modelName" to model.name,
                "supportsImage" to model.llmSupportImage,
                "supportsAudio" to model.llmSupportAudio,
            ),
        )
        ensureRuntimeModeForInputs(
            model = model,
            supportImage = imagePaths.isNotEmpty(),
            supportAudio = audioPaths.isNotEmpty(),
            onReady = {
                model.runtimeHelper.runInference(
                    model = model,
                    input = fullPrompt,
                    images = images,
                    audioClips = audioClips,
                    resultListener = { partial, done, thinking ->
                        emitEvent(
                            "chat_chunk",
                            mapOf(
                                "modelName" to model.name,
                                "text" to partial,
                                "thinking" to thinking,
                                "done" to done,
                            ),
                        )
                        if (done) {
                            emitEvent(
                                "chat_done",
                                mapOf("modelName" to model.name),
                            )
                        }
                    },
                    cleanUpListener = {
                        emitEvent(
                            "chat_cleanup",
                            mapOf("modelName" to model.name),
                        )
                    },
                    onError = { message ->
                        emitEvent(
                            "chat_error",
                            mapOf(
                                "modelName" to model.name,
                                "message" to message,
                            ),
                        )
                    },
                    coroutineScope = scope,
                )
            },
            onError = { message ->
                emitEvent(
                    "chat_error",
                    mapOf(
                        "modelName" to model.name,
                        "message" to message,
                    ),
                )
            },
        )
    }

    private fun ensureRuntimeModeForInputs(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val modelManager = getModelManager()
        val alreadyPrepared =
            model.instance != null &&
                activeLoadedModelName.get() == model.name &&
                activeSupportsImage.get() == supportImage &&
                activeSupportsAudio.get() == supportAudio
        if (alreadyPrepared) {
            onReady()
            return
        }

        updateOperation(
            status = "preparing_chat",
            message =
                when {
                    supportImage && supportAudio -> "Preparing image and voice chat..."
                    supportImage -> "Preparing image chat..."
                    supportAudio -> "Preparing voice chat..."
                    else -> "Preparing chat..."
                },
            modelName = model.name,
        )
        val currentTask = findPreferredTask(modelManager, model)
        modelManager.cleanupModel(
            context = applicationContext,
            task = currentTask,
            model = model,
            onDone = {
                model.initializing = true
                modelManager.setInitializationStatus(
                    model = model,
                    status =
                        ModelInitializationStatus(
                            status = ModelInitializationStatusType.INITIALIZING,
                        ),
                )
                model.runtimeHelper.initialize(
                    context = applicationContext,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    coroutineScope = scope,
                    onDone = { error ->
                        model.initializing = false
                        if (error.isNotEmpty()) {
                            modelManager.setInitializationStatus(
                                model = model,
                                status =
                                    ModelInitializationStatus(
                                        status = ModelInitializationStatusType.ERROR,
                                        error = error,
                                    ),
                            )
                            updateOperation(
                                status = "idle",
                                message = "",
                                modelName = model.name,
                            )
                            onError(error)
                        } else {
                            activeLoadedModelName.set(model.name)
                            activeSupportsImage.set(supportImage)
                            activeSupportsAudio.set(supportAudio)
                            modelManager.setInitializationStatus(
                                model = model,
                                status =
                                    ModelInitializationStatus(
                                        status = ModelInitializationStatusType.INITIALIZED,
                                    ),
                            )
                            updateOperation(
                                status = "idle",
                                message = "",
                                modelName = model.name,
                            )
                            onReady()
                        }
                    },
                )
            },
        )
    }

    fun importLocalModel(
        filePath: String,
        fileName: String,
        defaultMaxTokens: Int,
        defaultTopK: Int,
        defaultTopP: Double,
        defaultTemperature: Double,
        supportImage: Boolean,
        supportAudio: Boolean,
        supportTinyGarden: Boolean,
        supportMobileActions: Boolean,
        supportThinking: Boolean,
        accelerators: List<String>,
    ) {
        val source = File(filePath)
        require(source.exists()) { "Model file not found at '$filePath'." }
        val importsDir = File(applicationContext.getExternalFilesDir(null), "__imports")
        if (!importsDir.exists()) {
            importsDir.mkdirs()
        }
        val target = File(importsDir, fileName)
        if (source.absolutePath != target.absolutePath) {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }

        val importedModel =
            ImportedModel.newBuilder()
                .setFileName(fileName)
                .setFileSize(target.length())
                .setLlmConfig(
                    LlmConfig.newBuilder()
                        .addAllCompatibleAccelerators(accelerators)
                        .setDefaultMaxTokens(defaultMaxTokens)
                        .setDefaultTopk(defaultTopK)
                        .setDefaultTopp(defaultTopP.toFloat())
                        .setDefaultTemperature(defaultTemperature.toFloat())
                        .setSupportImage(supportImage)
                        .setSupportAudio(supportAudio)
                        .setSupportTinyGarden(supportTinyGarden)
                        .setSupportMobileActions(supportMobileActions)
                        .setSupportThinking(supportThinking)
                        .build(),
                )
                .build()
        getModelManager().addImportedLlmModel(importedModel)
        emitEvent("model_imported", mapOf("modelName" to fileName))
    }

    fun registerCustomRemoteModel(
        name: String,
        url: String,
        fileName: String,
        sizeInBytes: Long,
        minRamGb: Int?,
        defaultMaxTokens: Int,
        defaultTopK: Int,
        defaultTopP: Double,
        defaultTemperature: Double,
        supportImage: Boolean,
        supportAudio: Boolean,
        supportTinyGarden: Boolean,
        supportMobileActions: Boolean,
        supportThinking: Boolean,
        accelerators: List<String>,
    ) {
        val compatibleAccelerators =
            accelerators.mapNotNull { label ->
                when (label.lowercase()) {
                    Accelerator.CPU.label -> Accelerator.CPU
                    Accelerator.GPU.label -> Accelerator.GPU
                    Accelerator.NPU.label -> Accelerator.NPU
                    else -> null
                }
            }.ifEmpty { listOf(Accelerator.GPU) }
        val model =
            Model(
                name = name,
                displayName = name,
                url = url,
                sizeInBytes = sizeInBytes,
                minDeviceMemoryInGb = minRamGb,
                downloadFileName = fileName,
                version = "custom",
                configs =
                    createLlmChatConfigs(
                            defaultMaxToken = defaultMaxTokens,
                            defaultTopK = defaultTopK,
                            defaultTopP = defaultTopP.toFloat(),
                            defaultTemperature = defaultTemperature.toFloat(),
                            accelerators = compatibleAccelerators,
                            supportThinking = supportThinking,
                        )
                        .toMutableList(),
                imported = false,
                llmSupportImage = supportImage,
                llmSupportAudio = supportAudio,
                llmSupportTinyGarden = supportTinyGarden,
                llmSupportMobileActions = supportMobileActions,
                capabilities =
                    if (supportThinking) listOf(ModelCapability.LLM_THINKING) else emptyList(),
                capabilityToTaskTypes =
                    if (supportThinking) {
                        mapOf(
                            ModelCapability.LLM_THINKING to
                                listOf(
                                    BuiltInTaskId.LLM_CHAT,
                                    BuiltInTaskId.LLM_ASK_IMAGE,
                                    BuiltInTaskId.LLM_ASK_AUDIO,
                                ),
                        )
                    } else {
                        emptyMap()
                    },
                llmMaxToken = defaultMaxTokens,
                accelerators = compatibleAccelerators,
                isLlm = true,
                runtimeType = RuntimeType.LITERT_LM,
            )
        model.preProcess()
        getModelManager().addCustomRemoteLlmModel(model)
        customRemoteModelNames.add(model.name)
        emitEvent("custom_model_registered", mapOf("modelName" to model.name))
    }

    fun getModelManager(): ModelManagerViewModel {
        val existing = modelManagerViewModel
        if (existing != null) {
            ensureAllowlistLoaded(existing)
            return existing
        }

        return synchronized(this) {
            val current = modelManagerViewModel
            if (current != null) {
                ensureAllowlistLoaded(current)
                current
            } else {
                val created =
                    ModelManagerViewModel(
                        downloadRepository = entryPoint.downloadRepository(),
                        dataStoreRepository = entryPoint.dataStoreRepository(),
                        lifecycleProvider = entryPoint.lifecycleProvider(),
                        customTasks = entryPoint.customTasks(),
                        context = entryPoint.applicationContext(),
                    )
                modelManagerViewModel = created
                ensureAllowlistLoaded(created)
                created
            }
        }
    }

    private fun ensureAllowlistLoaded(modelManager: ModelManagerViewModel) {
        OpenAiServerState.modelManagerViewModel = modelManager
        if (!allowlistRequested) {
            synchronized(this) {
                if (!allowlistRequested) {
                    allowlistRequested = true
                    modelManager.loadModelAllowlist()
                }
            }
        }
    }

    private fun updateOperation(status: String, message: String, modelName: String? = null) {
        currentOperationStatus = status
        currentOperationMessage = message
        emitEvent(
            "operation",
            mapOf(
                "status" to status,
                "message" to message,
                "modelName" to modelName,
            ),
        )
    }

    private fun startDownloadProgressMonitor(modelName: String) {
        downloadMonitorJob?.cancel()
        downloadMonitorJob =
            scope.launch {
                while (true) {
                    val status =
                        getModelManager().uiState.value.modelDownloadStatus[modelName]?.status
                    if (status == null) {
                        emitEvent("model_download_progress", mapOf("modelName" to modelName))
                        break
                    }
                    emitEvent("model_download_progress", mapOf("modelName" to modelName))
                    if (
                        status != ModelDownloadStatusType.IN_PROGRESS &&
                            status != ModelDownloadStatusType.UNZIPPING
                    ) {
                        break
                    }
                    delay(800)
                }
            }
    }

    private fun stopDownloadProgressMonitor() {
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
    }

    private fun beginModelInitialization(
        modelManager: ModelManagerViewModel,
        targetTask: Task,
        targetModel: Model,
    ) {
        updateOperation(
            status = "loading",
            message = "Loading ${targetModel.displayName.ifEmpty { targetModel.name }} into memory...",
            modelName = targetModel.name,
        )
        modelManager.selectModel(targetModel)
        modelManager.initializeModel(
            context = applicationContext,
            task = targetTask,
            model = targetModel,
            force = true,
            onDone = {
                activeLoadedModelName.set(targetModel.name)
                activeSupportsImage.set(false)
                activeSupportsAudio.set(false)
                updateOperation(
                    status = "idle",
                    message = "",
                    modelName = targetModel.name,
                )
                emitEvent(
                    "model_loaded",
                    mapOf("modelName" to targetModel.name),
                )
            },
        )
    }

    private fun cleanupModelsSequentially(
        modelManager: ModelManagerViewModel,
        models: List<Model>,
        onDone: () -> Unit,
    ) {
        if (models.isEmpty()) {
            onDone()
            return
        }
        val head = models.first()
        val tail = models.drop(1)
        modelManager.cleanupModel(
            context = applicationContext,
            task = findPreferredTask(modelManager, head),
            model = head,
            onDone = {
                cleanupModelsSequentially(
                    modelManager = modelManager,
                    models = tail,
                    onDone = onDone,
                )
            },
        )
    }

    private fun emitEvent(type: String, payload: Map<String, Any?> = emptyMap()) {
        eventEmitter?.invoke(
            linkedMapOf<String, Any?>(
                "type" to type,
                "timestamp" to System.currentTimeMillis(),
            ).apply { putAll(payload) },
        )
    }

    private fun requireModel(modelName: String): Model {
        return getModelManager().getModelByName(modelName)
            ?: throw IllegalArgumentException("Model '$modelName' was not found.")
    }

    private fun findPreferredTask(
        modelManager: ModelManagerViewModel,
        model: Model,
    ): Task {
        val preferredTaskIds =
            listOf(
                BuiltInTaskId.LLM_CHAT,
                BuiltInTaskId.LLM_ASK_IMAGE,
                BuiltInTaskId.LLM_ASK_AUDIO,
                BuiltInTaskId.LLM_PROMPT_LAB,
                BuiltInTaskId.LLM_AGENT_CHAT,
            )
        return preferredTaskIds
            .asSequence()
            .mapNotNull { modelManager.getTaskById(it) }
            .firstOrNull { task -> task.models.any { it.name == model.name } }
            ?: modelManager.uiState.value.tasks.firstOrNull { task -> task.models.any { it.name == model.name } }
            ?: throw IllegalArgumentException("No task found for model '${model.name}'.")
    }

    private fun decodeBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) {
            return null
        }
        return try {
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, this)
                }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (maxDimension / sampleSize > 1536) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAudioBytes(path: String): ByteArray? {
        val file = File(path)
        if (!file.exists()) {
            return null
        }
        return file.readBytes()
    }

    companion object {
        @Volatile
        private var instance: NativeRuntimeCoordinator? = null

        fun getInstance(context: Context): NativeRuntimeCoordinator {
            val existing = instance
            if (existing != null) {
                return existing
            }

            return synchronized(this) {
                instance ?: NativeRuntimeCoordinator(context).also { instance = it }
            }
        }
    }
}
