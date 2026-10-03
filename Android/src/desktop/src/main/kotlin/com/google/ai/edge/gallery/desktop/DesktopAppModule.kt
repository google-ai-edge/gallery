package com.google.ai.edge.gallery.desktop

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.BenchmarkResultsSerializer
import com.google.ai.edge.gallery.CutoutsSerializer
import com.google.ai.edge.gallery.GalleryLifecycleProvider
import com.google.ai.edge.gallery.SettingsSerializer
import com.google.ai.edge.gallery.SkillsSerializer
import com.google.ai.edge.gallery.UserDataSerializer
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.sessions.DefaultLlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SummarizationContextCompactor
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatTask
import com.google.ai.edge.gallery.customtasks.agentchat.AgentChatViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.AgentToolsImpl
import com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.McpServersSerializer
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.examplecustomtask.ExampleCustomTaskViewModel
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTask
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsViewModel
import com.google.ai.edge.gallery.customtasks.tinygarden.TinyGardenTask
import com.google.ai.edge.gallery.customtasks.tinygarden.TinyGardenViewModel
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DefaultChatSessionRepository
import com.google.ai.edge.gallery.data.DefaultDataStoreRepository
import com.google.ai.edge.gallery.data.DesktopDownloadRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.proto.BenchmarkResults
import com.google.ai.edge.gallery.proto.CutoutCollection
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.OnboardingData
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.proto.Skills
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.skills.SkillManager
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.RuntimeToolsProvider
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkViewModel
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingSerializer
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmAskAudioTask
import com.google.ai.edge.gallery.ui.llmchat.LlmAskAudioViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageTask
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatTask
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmTestTask
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnTask
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnViewModel
import com.google.ai.edge.gallery.ui.modelmanager.HfExploreViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.notifications.NotificationsViewModel
import java.io.File
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers

class DesktopViewModelStoreOwner : ViewModelStoreOwner {
  override val viewModelStore: ViewModelStore = ViewModelStore()
}

object DesktopAppModule {
  val context: Context get() = Context.INSTANCE

  val settingsDataStore: DataStore<Settings> by lazy {
    DataStoreFactory.create(SettingsSerializer) { context.dataStoreFile("settings.pb") }
  }

  val cutoutsDataStore: DataStore<CutoutCollection> by lazy {
    DataStoreFactory.create(CutoutsSerializer) { context.dataStoreFile("cutouts.pb") }
  }

  val userDataDataStore: DataStore<UserData> by lazy {
    DataStoreFactory.create(UserDataSerializer) { context.dataStoreFile("user_data.pb") }
  }

  val benchmarkResultsStore: DataStore<BenchmarkResults> by lazy {
    DataStoreFactory.create(BenchmarkResultsSerializer) { context.dataStoreFile("benchmark_results.pb") }
  }

  val skillsDataStore: DataStore<Skills> by lazy {
    DataStoreFactory.create(SkillsSerializer) { context.dataStoreFile("skills.pb") }
  }

  val onboardingDataStore: DataStore<OnboardingData> by lazy {
    DataStoreFactory.create(OnboardingSerializer) { context.dataStoreFile("onboarding_data.pb") }
  }

  val mcpServersDataStore: DataStore<McpServers> by lazy {
    DataStoreFactory.create(McpServersSerializer) { context.dataStoreFile("mcp_servers.pb") }
  }

  val dataStoreRepository: DataStoreRepository by lazy {
    DefaultDataStoreRepository(
      settingsDataStore,
      userDataDataStore,
      cutoutsDataStore,
      benchmarkResultsStore,
      skillsDataStore,
    )
  }

  val chatSessionRepository: ChatSessionRepository by lazy {
    DefaultChatSessionRepository(userDataDataStore)
  }

  val lifecycleProvider: AppLifecycleProvider by lazy {
    GalleryLifecycleProvider()
  }

  val downloadRepository: DownloadRepository by lazy {
    DesktopDownloadRepository(context, lifecycleProvider)
  }

  val systemPromptRepository: SystemPromptRepository by lazy {
    SystemPromptRepository(userDataDataStore)
  }

  val huggingFaceApiClient: HuggingFaceApiClient by lazy {
    HuggingFaceApiClient(Dispatchers.IO)
  }

  val contextCompactor by lazy {
    SummarizationContextCompactor()
  }

  val llmSessionManager: LlmSessionManager by lazy {
    DefaultLlmSessionManager(context, chatSessionRepository, contextCompactor, Dispatchers.IO)
  }

  val aiChatExecutor: AgentRuntimeExecutor by lazy {
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )
  }

  val skillManager: SkillManager by lazy {
    SkillManager(dataStoreRepository, context)
  }

  val agentTools: AgentTools by lazy {
    AgentToolsImpl().apply { skillsProvider = skillManager }
  }

  val agentChatExecutor: AgentRuntimeExecutor by lazy {
    DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )
  }

  val notificationScheduleManager: NotificationScheduleManager by lazy {
    NotificationScheduleManager(context)
  }

  val customTasks: Set<CustomTask> by lazy {
    setOf(
      LlmChatTask(context, aiChatExecutor),
      LlmAskImageTask(context, aiChatExecutor),
      LlmAskAudioTask(context, aiChatExecutor),
      LlmSingleTurnTask(context),
      AgentChatTask(context, skillManager, agentTools, agentChatExecutor),
      TinyGardenTask(context),
      MobileActionsTask(context),
      LlmTestTask(context, aiChatExecutor),
    )
  }

  val modelManagerViewModel: ModelManagerViewModel by lazy {
    ModelManagerViewModel(
      downloadRepository = downloadRepository,
      dataStoreRepository = dataStoreRepository,
      lifecycleProvider = lifecycleProvider,
      customTasks = customTasks,
      systemPromptRepository = systemPromptRepository,
      huggingFaceApiClient = huggingFaceApiClient,
      context = context,
    )
  }

  val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
      return when (modelClass) {
        ModelManagerViewModel::class -> modelManagerViewModel as T
        TosViewModel::class -> TosViewModel(dataStoreRepository) as T
        LlmChatViewModel::class -> LlmChatViewModel(systemPromptRepository, aiChatExecutor, llmSessionManager) as T
        LlmAskImageViewModel::class -> LlmAskImageViewModel(systemPromptRepository, aiChatExecutor, llmSessionManager) as T
        LlmAskAudioViewModel::class -> LlmAskAudioViewModel(systemPromptRepository, aiChatExecutor, llmSessionManager) as T
        LlmSingleTurnViewModel::class -> LlmSingleTurnViewModel() as T
        AgentChatViewModel::class -> AgentChatViewModel(systemPromptRepository, agentChatExecutor, llmSessionManager) as T
        SkillManagerViewModel::class -> SkillManagerViewModel(skillManager) as T
        McpManagerViewModel::class -> McpManagerViewModel(mcpServersDataStore, userDataDataStore) as T
        MobileActionsViewModel::class -> MobileActionsViewModel(context) as T
        HoldToDictateViewModel::class -> HoldToDictateViewModel(context) as T
        TinyGardenViewModel::class -> TinyGardenViewModel(context, dataStoreRepository) as T
        BenchmarkViewModel::class -> BenchmarkViewModel(context, dataStoreRepository) as T
        OnboardingViewModel::class -> OnboardingViewModel(onboardingDataStore) as T
        HfExploreViewModel::class -> HfExploreViewModel(huggingFaceApiClient, dataStoreRepository) as T
        NotificationsViewModel::class -> NotificationsViewModel(notificationScheduleManager) as T
        ExampleCustomTaskViewModel::class -> ExampleCustomTaskViewModel() as T
        else -> {
          try {
            modelClass.java.getDeclaredConstructor().newInstance()
          } catch (e: Exception) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.qualifiedName}", e)
          }
        }
      }
    }
  }

  fun initializeAllowlistIfMissing() {
    try {
      val modelsDir = com.google.ai.edge.gallery.common.getModelStorageDir(context)
      val targetFile = File(modelsDir, "model_allowlist.json")
      if (!targetFile.exists()) {
        val stream =
          DesktopAppModule::class.java.classLoader.getResourceAsStream(
            "model_allowlists/1_0_19.json"
          )
        if (stream != null) {
          modelsDir.mkdirs()
          stream.use { input -> targetFile.outputStream().use { input.copyTo(it) } }
        }
      }
    } catch (e: Exception) {
      android.util.Log.w("DesktopAppModule", "Failed to seed bundled model allowlist", e)
    }
  }
}
