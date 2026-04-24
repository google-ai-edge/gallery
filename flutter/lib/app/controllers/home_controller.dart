import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart' hide Response;
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../core/native/native_bridge.dart';
import '../core/server/openai_api_server.dart';
import '../core/storage/app_storage.dart';
import '../models/chat_message_item.dart';
import '../models/chat_session_item.dart';
import '../models/native_model_inventory.dart';
import '../models/native_model_summary.dart';
import '../models/native_server_status.dart';

class HomeController extends GetxController {
  HomeController() {
    _apiServer = OpenAiApiServer(
      nativeBridge: _nativeBridge,
      inventoryProvider: _nativeBridge.fetchModelInventory,
    );
  }

  final NativeBridge _nativeBridge = Get.find<NativeBridge>();
  final AppStorage _storage = Get.find<AppStorage>();
  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 12),
      receiveTimeout: const Duration(seconds: 12),
    ),
  );
  final AudioRecorder _audioRecorder = AudioRecorder();

  final RxInt tabIndex = 0.obs;
  final RxBool bootstrapping = true.obs;
  final RxBool busy = false.obs;
  final RxBool chatInProgress = false.obs;
  final RxBool isRecording = false.obs;
  final RxBool serverActionInProgress = false.obs;
  final RxBool localServerTestInProgress = false.obs;
  final RxBool externalServerTestInProgress = false.obs;
  final RxBool localServerTestSucceeded = false.obs;
  final RxBool externalServerTestSucceeded = false.obs;
  final RxBool tunnelStatusIsError = false.obs;
  final RxString localServerTestMessage = ''.obs;
  final RxString externalServerTestMessage = ''.obs;
  final RxString tunnelStatusMessage = ''.obs;
  final RxString modelFilter = 'All'.obs;
  final RxString composerText = ''.obs;
  final RxString attachedDocumentName = ''.obs;
  final RxString attachedDocumentContent = ''.obs;
  final RxBool useTunnel = true.obs;
  final RxDouble temperature = 1.0.obs;
  final RxInt maxTokens = 2048.obs;
  final RxString systemPrompt = ''.obs;
  final RxString huggingFaceToken = ''.obs;
  final RxString tunnelProvider = 'cloudflare'.obs;
  final RxString cloudflareTunnelToken = ''.obs;
  final RxString cloudflarePublicUrl = ''.obs;
  final RxString ngrokAuthToken = ''.obs;
  final RxString ngrokDomain = ''.obs;
  final RxList<NativeModelSummary> visibleModels = <NativeModelSummary>[].obs;
  final RxList<ChatMessageItem> messages = <ChatMessageItem>[].obs;
  final RxList<ChatSessionItem> chatSessions = <ChatSessionItem>[].obs;
  final RxList<String> attachedImagePaths = <String>[].obs;
  final RxList<String> attachedAudioPaths = <String>[].obs;
  final RxSet<String> pendingDownloadModels = <String>{}.obs;
  final RxSet<String> pendingLoadModels = <String>{}.obs;
  final RxSet<String> pendingUnloadModels = <String>{}.obs;
  final Rxn<NativeModelInventory> inventory = Rxn<NativeModelInventory>();
  final Rxn<NativeServerStatus> serverStatus = Rxn<NativeServerStatus>();
  final RxnString currentChatId = RxnString();

  final TextEditingController inputController = TextEditingController();

  late final OpenAiApiServer _apiServer;
  Timer? _poller;
  StreamSubscription<Map<String, dynamic>>? _eventSubscription;
  String? _streamingMessageId;
  DateTime? _tunnelRequestedAt;
  final Map<String, String> _streamingRawText = <String, String>{};

  String? get activeModelName =>
      inventory.value?.activeModelName ?? serverStatus.value?.activeModelName;

  bool get activeModelSupportsThinking {
    final name = activeModelName;
    if (name == null || name.isEmpty) {
      return false;
    }
    for (final model
        in inventory.value?.models ?? const <NativeModelSummary>[]) {
      if (model.name == name) {
        return model.supportsThinking;
      }
    }
    return false;
  }

  bool get hasAttachedDocument => attachedDocumentContent.isNotEmpty;

  @override
  void onInit() {
    super.onInit();
    useTunnel.value = _storage.serverTunnelEnabled;
    temperature.value = _storage.temperature;
    maxTokens.value = _storage.maxTokens;
    systemPrompt.value = _storage.systemPrompt;
    huggingFaceToken.value = _storage.huggingFaceToken;
    tunnelProvider.value = _storage.serverTunnelProvider;
    cloudflareTunnelToken.value = _storage.cloudflareTunnelToken;
    cloudflarePublicUrl.value = _storage.cloudflarePublicUrl;
    ngrokAuthToken.value = _storage.ngrokAuthToken;
    ngrokDomain.value = _storage.ngrokDomain;
    _loadSavedChats();
    _eventSubscription = _nativeBridge.events.listen(_handleNativeEvent);
    unawaited(_bootstrap());
  }

  @override
  void onClose() {
    _poller?.cancel();
    _eventSubscription?.cancel();
    unawaited(_apiServer.stop());
    inputController.dispose();
    unawaited(_audioRecorder.dispose());
    unawaited(WakelockPlus.disable());
    super.onClose();
  }

  Future<void> _bootstrap() async {
    if (huggingFaceToken.value.isNotEmpty) {
      await _nativeBridge.saveAccessToken(huggingFaceToken.value);
    }
    await _nativeBridge.saveCloudflareTunnelConfig(
      tunnelToken: cloudflareTunnelToken.value,
      publicUrl: cloudflarePublicUrl.value,
    );
    await _nativeBridge.saveNgrokConfig(
      authToken: ngrokAuthToken.value,
      domain: ngrokDomain.value,
    );
    await _syncSavedCustomModels();
    await refreshRuntime();
    _poller = Timer.periodic(
      const Duration(seconds: 1),
      (_) => refreshRuntime(silent: true),
    );
    bootstrapping.value = false;
  }

  Future<void> refreshRuntime({bool silent = false}) async {
    if (!silent) {
      busy.value = true;
    }
    try {
      final results = await Future.wait<dynamic>(<Future<dynamic>>[
        _nativeBridge.fetchModelInventory(),
        _nativeBridge.fetchServerStatus(),
      ]);
      inventory.value = results[0] as NativeModelInventory;
      serverStatus.value = results[1] as NativeServerStatus;
      for (final model
          in inventory.value?.models ?? const <NativeModelSummary>[]) {
        if (model.isDownloading ||
            model.isDownloaded ||
            model.isPartiallyDownloaded ||
            model.downloadStatus == 'FAILED' ||
            model.downloadStatus == 'NOT_DOWNLOADED') {
          pendingDownloadModels.remove(model.name);
        }
      }
      _applyFilter();
      _storage.serverUrl = serverStatus.value?.localUrl;
      _storage.lastRefreshAt = DateTime.now();
      _syncTunnelStatusMessage();
      await _syncWakelock();
    } catch (error) {
      Get.snackbar('Runtime error', _friendlyErrorText(error));
    } finally {
      busy.value = false;
    }
  }

  void setTab(int index) {
    tabIndex.value = index;
  }

  void setModelFilter(String value) {
    modelFilter.value = value;
    _applyFilter();
  }

  void updateComposer(String value) {
    composerText.value = value;
  }

  Future<void> startServer() async {
    serverActionInProgress.value = true;
    localServerTestMessage.value = '';
    externalServerTestMessage.value = '';
    try {
      _storage.serverTunnelEnabled = useTunnel.value;
      _storage.serverTunnelProvider = tunnelProvider.value;
      _tunnelRequestedAt = useTunnel.value ? DateTime.now() : null;
      tunnelStatusIsError.value = false;
      tunnelStatusMessage.value = '';
      await _apiServer.start(port: 8080);
      await _nativeBridge.startNativeServer(
        useTunnel: useTunnel.value,
        tunnelProvider: tunnelProvider.value,
      );
      await _waitForServerState(true);
    } catch (error) {
      await _apiServer.stop();
      Get.snackbar('Server error', _friendlyErrorText(error));
    } finally {
      serverActionInProgress.value = false;
      await _syncWakelock();
    }
  }

  Future<void> stopServer() async {
    serverActionInProgress.value = true;
    localServerTestMessage.value = '';
    externalServerTestMessage.value = '';
    tunnelStatusMessage.value = '';
    tunnelStatusIsError.value = false;
    _tunnelRequestedAt = null;
    try {
      await _apiServer.stop();
      await _nativeBridge.stopNativeServer();
      await _waitForServerState(false);
    } catch (error) {
      Get.snackbar('Server error', _friendlyErrorText(error));
    } finally {
      serverActionInProgress.value = false;
      await _syncWakelock();
    }
  }

  Future<void> testLocalServerHealth() =>
      _testServerHealth(local: true, url: serverStatus.value?.localUrl ?? '');

  Future<void> testExternalServerHealth() =>
      _testServerHealth(local: false, url: serverStatus.value?.publicUrl ?? '');

  Future<void> downloadModel(String name) async {
    final activeDownload = activeDownloadingModelName;
    if (activeDownload != null && activeDownload != name) {
      String label = activeDownload;
      final models = inventory.value?.models ?? const <NativeModelSummary>[];
      for (final model in models) {
        if (model.name == activeDownload) {
          label = model.displayName.isEmpty ? model.name : model.displayName;
          break;
        }
      }
      Get.snackbar(
        'One download at a time',
        '$label is already downloading. Finish or stop it before starting another model.',
      );
      return;
    }
    pendingDownloadModels.add(name);
    await _syncWakelock();
    try {
      await _nativeBridge.downloadModel(name);
      await refreshRuntime(silent: true);
    } catch (error) {
      pendingDownloadModels.remove(name);
      await _syncWakelock();
      Get.snackbar('Download blocked', _friendlyErrorText(error));
    }
  }

  Future<void> cancelDownload(String name) async {
    try {
      await _nativeBridge.cancelDownloadModel(name);
      await refreshRuntime(silent: true);
    } finally {
      pendingDownloadModels.remove(name);
      await _syncWakelock();
    }
  }

  Future<void> deleteModel(NativeModelSummary model) async {
    await _nativeBridge.deleteModel(model.name);
    if (model.isCustomRemote) {
      await _storage.removeCustomModel(model.name);
    }
    await refreshRuntime();
  }

  Future<void> loadModel(String name) async {
    pendingLoadModels.add(name);
    await _syncWakelock();
    try {
      await _nativeBridge.loadModel(name);
      await refreshRuntime(silent: true);
    } catch (error) {
      pendingLoadModels.remove(name);
      await _syncWakelock();
      Get.snackbar('Load failed', _friendlyErrorText(error));
    }
  }

  Future<void> unloadModel(String name) async {
    pendingUnloadModels.add(name);
    await _syncWakelock();
    try {
      await _nativeBridge.unloadModel(name);
      await refreshRuntime(silent: true);
    } catch (error) {
      pendingUnloadModels.remove(name);
      await _syncWakelock();
      Get.snackbar('Unload failed', _friendlyErrorText(error));
    }
  }

  Future<void> resetConversation() async {
    final modelName = activeModelName;
    if (modelName == null || modelName.isEmpty) {
      createNewChat();
      return;
    }
    await _nativeBridge.resetConversation(modelName);
    createNewChat(persistEmptyChat: true);
  }

  Future<void> stopGeneration() async {
    final modelName = activeModelName;
    if (modelName == null || modelName.isEmpty) {
      return;
    }
    await _nativeBridge.stopChatGeneration(modelName);
    chatInProgress.value = false;
    await _syncWakelock();
  }

  Future<void> pickImages() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.image,
      allowMultiple: true,
    );
    if (result == null) {
      return;
    }
    attachedImagePaths.assignAll(
      result.files.map((file) => file.path).whereType<String>(),
    );
  }

  Future<void> toggleRecording() async {
    if (isRecording.value) {
      final path = await _audioRecorder.stop();
      isRecording.value = false;
      await _syncWakelock();
      if (path != null && path.isNotEmpty) {
        attachedAudioPaths.assignAll(<String>[path]);
      }
      return;
    }

    final hasPermission = await _audioRecorder.hasPermission();
    if (!hasPermission) {
      Get.snackbar(
        'Microphone needed',
        'Allow microphone access to record a voice prompt.',
      );
      return;
    }

    final directory = await getTemporaryDirectory();
    final path =
        '${directory.path}${Platform.pathSeparator}voice_${DateTime.now().millisecondsSinceEpoch}.wav';
    await _audioRecorder.start(
      const RecordConfig(
        encoder: AudioEncoder.wav,
        sampleRate: 16000,
        numChannels: 1,
        bitRate: 128000,
      ),
      path: path,
    );
    isRecording.value = true;
    await _syncWakelock();
  }

  Future<void> pickDocument() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const <String>[
        'txt',
        'md',
        'json',
        'csv',
        'yaml',
        'yml',
        'xml',
        'html',
        'log',
      ],
    );
    if (result == null || result.files.single.path == null) {
      return;
    }
    final file = File(result.files.single.path!);
    attachedDocumentName.value = result.files.single.name;
    attachedDocumentContent.value = await file.readAsString();
  }

  void clearImages() {
    attachedImagePaths.clear();
  }

  void clearAudio() {
    attachedAudioPaths.clear();
  }

  void clearDocument() {
    attachedDocumentName.value = '';
    attachedDocumentContent.value = '';
  }

  Future<void> sendMessage() async {
    final modelName = activeModelName;
    final text = inputController.text.trim();
    if (modelName == null || modelName.isEmpty) {
      Get.snackbar(
        'No model loaded',
        'Load a model from the Models tab first.',
      );
      return;
    }
    if (text.isEmpty &&
        attachedImagePaths.isEmpty &&
        attachedAudioPaths.isEmpty &&
        attachedDocumentContent.isEmpty) {
      return;
    }

    final sessionId = _ensureChatSession();
    messages.add(
      ChatMessageItem(
        id: DateTime.now().microsecondsSinceEpoch.toString(),
        role: 'user',
        text: text,
        imagePaths: attachedImagePaths.toList(growable: false),
        audioPaths: attachedAudioPaths.toList(growable: false),
        documentName: attachedDocumentName.value.isEmpty
            ? null
            : attachedDocumentName.value,
      ),
    );
    _persistCurrentChat(sessionId: sessionId, modelName: modelName);

    final imagePaths = attachedImagePaths.toList(growable: false);
    final audioPaths = attachedAudioPaths.toList(growable: false);
    final documentContext = attachedDocumentContent.value;

    inputController.clear();
    composerText.value = '';
    clearImages();
    clearAudio();
    clearDocument();

    chatInProgress.value = true;
    await _syncWakelock();
    _streamingMessageId = DateTime.now().millisecondsSinceEpoch.toString();
    messages.add(
      ChatMessageItem(
        id: _streamingMessageId!,
        role: 'assistant',
        text: '',
        isStreaming: true,
      ),
    );

    try {
      await _nativeBridge.sendChatMessage(
        modelName: modelName,
        prompt: text,
        documentContext: documentContext.isEmpty ? null : documentContext,
        imagePaths: imagePaths,
        audioPaths: audioPaths,
        systemPrompt: systemPrompt.value.isEmpty ? null : systemPrompt.value,
        temperature: temperature.value,
        maxTokens: maxTokens.value,
      );
    } catch (error) {
      chatInProgress.value = false;
      await _syncWakelock();
      final message = _friendlyErrorText(error);
      _replaceStreamingMessageWithError(message);
      _persistCurrentChat(sessionId: sessionId, modelName: modelName);
      Get.snackbar('Chat failed', message);
    }
  }

  bool isDownloadPending(String modelName) =>
      pendingDownloadModels.contains(modelName);

  bool isLoadPending(String modelName) => pendingLoadModels.contains(modelName);

  bool isUnloadPending(String modelName) =>
      pendingUnloadModels.contains(modelName);

  String? get activeDownloadingModelName {
    final models = inventory.value?.models ?? const <NativeModelSummary>[];
    for (final model in models) {
      if (model.isDownloading) {
        return model.name;
      }
    }
    if (pendingDownloadModels.isNotEmpty) {
      return pendingDownloadModels.first;
    }
    return null;
  }

  Future<void> importLocalModel() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const <String>['task', 'litertlm', 'bin', 'gguf'],
    );
    if (result == null || result.files.single.path == null) {
      return;
    }
    final file = result.files.single;
    await _nativeBridge.importLocalModel(
      filePath: file.path!,
      fileName: file.name,
      defaultMaxTokens: 2048,
      defaultTopK: 40,
      defaultTopP: 0.95,
      defaultTemperature: 0.8,
      supportImage: false,
      supportAudio: false,
      supportTinyGarden: false,
      supportMobileActions: false,
      supportThinking: false,
      accelerators: const <String>['gpu', 'cpu'],
    );
    await refreshRuntime();
  }

  Future<void> registerCustomModel(Map<String, dynamic> payload) async {
    await _nativeBridge.registerCustomRemoteModel(
      name: payload['name'] as String,
      url: payload['url'] as String,
      fileName: payload['fileName'] as String,
      sizeInBytes: payload['sizeInBytes'] as int,
      minRamGb: payload['minRamGb'] as int?,
      defaultMaxTokens: payload['defaultMaxTokens'] as int,
      defaultTopK: payload['defaultTopK'] as int,
      defaultTopP: payload['defaultTopP'] as double,
      defaultTemperature: payload['defaultTemperature'] as double,
      supportImage: payload['supportImage'] as bool,
      supportAudio: payload['supportAudio'] as bool,
      supportTinyGarden: payload['supportTinyGarden'] as bool,
      supportMobileActions: payload['supportMobileActions'] as bool,
      supportThinking: payload['supportThinking'] as bool,
      accelerators: (payload['accelerators'] as List<dynamic>).cast<String>(),
    );
    await _storage.upsertCustomModel(payload);
    await refreshRuntime();
  }

  Future<void> saveSettings({
    required String nextSystemPrompt,
    required double nextTemperature,
    required int nextMaxTokens,
    required String nextHuggingFaceToken,
    required String nextTunnelProvider,
    required String nextCloudflareTunnelToken,
    required String nextCloudflarePublicUrl,
    required String nextNgrokAuthToken,
    required String nextNgrokDomain,
  }) async {
    systemPrompt.value = nextSystemPrompt.trim();
    temperature.value = nextTemperature;
    maxTokens.value = nextMaxTokens;
    huggingFaceToken.value = nextHuggingFaceToken.trim();
    tunnelProvider.value =
        nextTunnelProvider.trim() == 'ngrok' ? 'ngrok' : 'cloudflare';
    cloudflareTunnelToken.value = nextCloudflareTunnelToken.trim();
    cloudflarePublicUrl.value = nextCloudflarePublicUrl.trim();
    ngrokAuthToken.value = nextNgrokAuthToken.trim();
    ngrokDomain.value = nextNgrokDomain.trim();

    _storage.systemPrompt = systemPrompt.value;
    _storage.temperature = temperature.value;
    _storage.maxTokens = maxTokens.value;
    _storage.huggingFaceToken = huggingFaceToken.value;
    _storage.serverTunnelProvider = tunnelProvider.value;
    _storage.cloudflareTunnelToken = cloudflareTunnelToken.value;
    _storage.cloudflarePublicUrl = cloudflarePublicUrl.value;
    _storage.ngrokAuthToken = ngrokAuthToken.value;
    _storage.ngrokDomain = ngrokDomain.value;

    if (huggingFaceToken.value.isEmpty) {
      await _nativeBridge.clearAccessToken();
    } else {
      await _nativeBridge.saveAccessToken(huggingFaceToken.value);
    }
    await _nativeBridge.saveCloudflareTunnelConfig(
      tunnelToken: cloudflareTunnelToken.value,
      publicUrl: cloudflarePublicUrl.value,
    );
    await _nativeBridge.saveNgrokConfig(
      authToken: ngrokAuthToken.value,
      domain: ngrokDomain.value,
    );

    Get.snackbar('Settings saved', 'Runtime preferences updated.');
  }

  void selectTunnelProvider(String provider) {
    tunnelProvider.value = provider == 'ngrok' ? 'ngrok' : 'cloudflare';
    _storage.serverTunnelProvider = tunnelProvider.value;
  }

  void createNewChat({bool persistEmptyChat = true}) {
    messages.clear();
    inputController.clear();
    composerText.value = '';
    clearImages();
    clearAudio();
    clearDocument();
    final id = DateTime.now().microsecondsSinceEpoch.toString();
    currentChatId.value = id;
    _storage.activeChatId = id;
    if (persistEmptyChat) {
      _persistCurrentChat(sessionId: id, modelName: activeModelName);
    }
  }

  Future<void> openChatSession(String id) async {
    final session = _chatSessionById(chatSessions, id);
    if (session == null) {
      return;
    }
    currentChatId.value = session.id;
    _storage.activeChatId = session.id;
    messages.assignAll(session.messages);
    inputController.clear();
    composerText.value = '';
    clearImages();
    clearAudio();
    clearDocument();
    if (activeModelName != null && activeModelName!.isNotEmpty) {
      await _nativeBridge.resetConversation(activeModelName!);
    }
    update();
  }

  Future<void> deleteChatSession(String id) async {
    chatSessions.removeWhere((item) => item.id == id);
    await _storage.saveChatSessions(
      chatSessions.map((session) => session.toMap()).toList(growable: false),
    );

    if (currentChatId.value == id) {
      if (chatSessions.isEmpty) {
        createNewChat();
      } else {
        await openChatSession(chatSessions.first.id);
      }
    }
  }

  Future<void> _syncSavedCustomModels() async {
    for (final model in _storage.customModels) {
      try {
        await _nativeBridge.registerCustomRemoteModel(
          name: model['name'] as String,
          url: model['url'] as String,
          fileName: model['fileName'] as String,
          sizeInBytes: model['sizeInBytes'] as int,
          minRamGb: model['minRamGb'] as int?,
          defaultMaxTokens: model['defaultMaxTokens'] as int,
          defaultTopK: model['defaultTopK'] as int,
          defaultTopP: (model['defaultTopP'] as num).toDouble(),
          defaultTemperature: (model['defaultTemperature'] as num).toDouble(),
          supportImage: model['supportImage'] as bool,
          supportAudio: model['supportAudio'] as bool,
          supportTinyGarden: model['supportTinyGarden'] as bool,
          supportMobileActions: model['supportMobileActions'] as bool,
          supportThinking: model['supportThinking'] as bool,
          accelerators: (model['accelerators'] as List<dynamic>).cast<String>(),
        );
      } catch (_) {}
    }
  }

  void _applyFilter() {
    final items = inventory.value?.models ?? const <NativeModelSummary>[];
    switch (modelFilter.value) {
      case 'Downloaded':
        visibleModels.assignAll(items.where((model) => model.isDownloaded));
        break;
      case 'Media':
        visibleModels.assignAll(
          items.where((model) => model.supportsImage || model.supportsAudio),
        );
        break;
      case 'Custom':
        visibleModels.assignAll(
          items.where((model) => model.imported || model.isCustomRemote),
        );
        break;
      default:
        visibleModels.assignAll(items);
    }
  }

  void _handleNativeEvent(Map<String, dynamic> event) {
    final type = event['type'] as String? ?? '';
    _apiServer.handleNativeEvent(event);
    if (type.startsWith('api_chat_')) {
      return;
    }
    switch (type) {
      case 'chat_chunk':
        _appendAssistantChunk(
          event['text'] as String? ?? '',
          event['thinking'] as String? ?? '',
          done: event['done'] as bool? ?? false,
        );
        break;
      case 'chat_done':
        chatInProgress.value = false;
        _finalizeStreamingMessage();
        _persistCurrentChat(
          sessionId: _ensureChatSession(),
          modelName: activeModelName,
        );
        unawaited(_syncWakelock());
        break;
      case 'chat_error':
        chatInProgress.value = false;
        final message = _friendlyErrorText(
          event['message'] as String? ?? 'Unknown native error',
        );
        _replaceStreamingMessageWithError(message);
        _persistCurrentChat(
          sessionId: _ensureChatSession(),
          modelName: activeModelName,
        );
        unawaited(_syncWakelock());
        break;
      case 'model_download_requested':
      case 'model_download_progress':
        unawaited(refreshRuntime(silent: true));
        break;
      default:
        final modelName = event['modelName'] as String?;
        if (modelName != null) {
          pendingDownloadModels.remove(modelName);
          pendingLoadModels.remove(modelName);
          pendingUnloadModels.remove(modelName);
        }
        unawaited(refreshRuntime(silent: true));
    }
  }

  void _appendAssistantChunk(
    String chunk,
    String thinking, {
    required bool done,
  }) {
    final id = _streamingMessageId;
    if (id == null) {
      return;
    }
    final index = messages.indexWhere((message) => message.id == id);
    if (index < 0) {
      return;
    }
    final current = messages[index];

    final nativeOutput =
        chunk + (thinking.isNotEmpty ? '\n<think>\n$thinking\n</think>\n' : '');

    final currentRaw = _streamingRawText[id] ?? '';
    final newRaw = currentRaw + nativeOutput;
    _streamingRawText[id] = newRaw;

    final parsed = _parseFullRawText(newRaw);

    messages[index] = current.copyWith(
      text: parsed.text,
      thinking: parsed.thinking,
      isStreaming: !done,
    );
  }

  void _finalizeStreamingMessage() {
    final id = _streamingMessageId;
    _streamingRawText.remove(id);
    _streamingMessageId = null;

    final index = messages.indexWhere((message) => message.id == id);
    if (index >= 0) {
      final current = messages[index];
      messages[index] = current.copyWith(
        text: current.text.trim(),
        thinking: current.thinking.trim(),
        isStreaming: false,
      );
      _persistCurrentChat(
        sessionId: currentChatId.value!,
        modelName: activeModelName,
      );
    }
  }

  ({String text, String thinking}) _parseFullRawText(String rawText) {
    var remaining = rawText;
    final thinkingParts = <String>[];

    final completePattern = RegExp(r'<think>(.*?)</think>', dotAll: true);
    for (final match in completePattern.allMatches(remaining)) {
      final part = match.group(1);
      if (part != null && part.trim().isNotEmpty) {
        thinkingParts.add(part);
      }
    }
    remaining = remaining.replaceAll(completePattern, '');

    final openIdx = remaining.lastIndexOf('<think>');
    if (openIdx >= 0) {
      final trailing = remaining.substring(openIdx + 7);
      if (trailing.trim().isNotEmpty) {
        thinkingParts.add(trailing);
      }
      remaining = remaining.substring(0, openIdx);
    }

    remaining = remaining.replaceAll('</think>', '');

    return (text: remaining, thinking: thinkingParts.join('\n\n'));
  }

  void _replaceStreamingMessageWithError(String message) {
    final id = _streamingMessageId;
    if (id != null) {
      final index = messages.indexWhere((item) => item.id == id);
      if (index >= 0) {
        messages[index] = ChatMessageItem(
          id: id,
          role: 'assistant',
          text: message,
          isError: true,
        );
      }
    } else {
      messages.add(
        ChatMessageItem(
          id: DateTime.now().microsecondsSinceEpoch.toString(),
          role: 'assistant',
          text: message,
          isError: true,
        ),
      );
    }
    _streamingMessageId = null;
  }

  void _loadSavedChats() {
    final saved =
        _storage.chatSessions
            .map(ChatSessionItem.fromMap)
            .toList(growable: false)
          ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    chatSessions.assignAll(saved);
    if (saved.isEmpty) {
      createNewChat();
      return;
    }

    final preferredId = _storage.activeChatId;
    final selected = _chatSessionById(saved, preferredId) ?? saved.first;
    currentChatId.value = selected.id;
    _storage.activeChatId = selected.id;
    messages.assignAll(selected.messages);
  }

  String _ensureChatSession() {
    final existing = currentChatId.value;
    if (existing != null && existing.isNotEmpty) {
      return existing;
    }
    final id = DateTime.now().microsecondsSinceEpoch.toString();
    currentChatId.value = id;
    _storage.activeChatId = id;
    return id;
  }

  Future<void> _persistCurrentChat({
    required String sessionId,
    required String? modelName,
  }) async {
    final title = _deriveChatTitle(messages);
    final session = ChatSessionItem(
      id: sessionId,
      title: title,
      updatedAt: DateTime.now(),
      messages: messages.toList(growable: false),
      modelName: modelName,
    );

    final updated = chatSessions.toList(growable: true);
    final index = updated.indexWhere((item) => item.id == sessionId);
    if (index >= 0) {
      updated[index] = session;
    } else {
      updated.add(session);
    }
    updated.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    chatSessions.assignAll(updated);
    _storage.activeChatId = sessionId;
    await _storage.saveChatSessions(
      updated.map((item) => item.toMap()).toList(growable: false),
    );
  }

  String _deriveChatTitle(List<ChatMessageItem> items) {
    final firstUser = items
        .where((item) => item.role == 'user')
        .cast<ChatMessageItem?>()
        .firstWhere((item) => item != null, orElse: () => null);
    if (firstUser == null) {
      return 'New chat';
    }
    if (firstUser.text.trim().isNotEmpty) {
      final compact = firstUser.text.trim().replaceAll('\n', ' ');
      return compact.length > 42 ? '${compact.substring(0, 42)}...' : compact;
    }
    if (firstUser.documentName != null) {
      return 'Doc chat: ${firstUser.documentName}';
    }
    if (firstUser.imagePaths.isNotEmpty) {
      return 'Image chat';
    }
    if (firstUser.audioPaths.isNotEmpty) {
      return 'Voice chat';
    }
    return 'New chat';
  }

  ChatSessionItem? _chatSessionById(
    Iterable<ChatSessionItem> items,
    String? id,
  ) {
    if (id == null || id.isEmpty) {
      return null;
    }
    for (final item in items) {
      if (item.id == id) {
        return item;
      }
    }
    return null;
  }

  Future<void> _waitForServerState(bool shouldBeRunning) async {
    for (var attempt = 0; attempt < 20; attempt++) {
      await Future<void>.delayed(const Duration(seconds: 1));
      final nextStatus = await _nativeBridge.fetchServerStatus();
      serverStatus.value = nextStatus;
      _syncTunnelStatusMessage();
      if (nextStatus.isRunning == shouldBeRunning) {
        await refreshRuntime(silent: true);
        return;
      }
    }
    throw TimeoutException(
      shouldBeRunning
          ? 'Server did not finish starting in time.'
          : 'Server did not finish stopping in time.',
    );
  }

  Future<void> _testServerHealth({
    required bool local,
    required String url,
  }) async {
    if (url.isEmpty) {
      Get.snackbar(
        local ? 'No local URL' : 'No external URL',
        local
            ? 'Start the server first to test the local health endpoint.'
            : 'Start the server with tunnel enabled to test the external health endpoint.',
      );
      return;
    }

    final inProgress = local
        ? localServerTestInProgress
        : externalServerTestInProgress;
    final succeeded = local
        ? localServerTestSucceeded
        : externalServerTestSucceeded;
    final message = local ? localServerTestMessage : externalServerTestMessage;

    inProgress.value = true;
    succeeded.value = false;
    message.value = '';
    try {
      final response = await _getHealthWithRetry(
        '${_normalizeUrl(url)}/health',
        attempts: local ? 1 : 8,
      );
      succeeded.value = true;
      message.value =
          '${local ? 'Local' : 'External'} health endpoint is live. Status ${response.statusCode}.';
    } catch (error) {
      succeeded.value = false;
      message.value =
          '${local ? 'Local' : 'External'} health test failed: ${_friendlyErrorText(error)}';
    } finally {
      inProgress.value = false;
    }
  }

  Future<Response<dynamic>> _getHealthWithRetry(
    String url, {
    required int attempts,
  }) async {
    Object? lastError;
    for (var attempt = 0; attempt < attempts; attempt++) {
      try {
        return await _dio.get<dynamic>(url);
      } catch (error) {
        lastError = error;
        if (attempt == attempts - 1) {
          break;
        }
        await Future<void>.delayed(Duration(seconds: 2 + attempt));
        await refreshRuntime(silent: true);
      }
    }
    throw lastError ?? StateError('Health check failed.');
  }

  Future<void> _syncWakelock() async {
    final models = inventory.value?.models ?? const <NativeModelSummary>[];
    final keepAwake =
        serverActionInProgress.value ||
        chatInProgress.value ||
        isRecording.value ||
        pendingDownloadModels.isNotEmpty ||
        pendingLoadModels.isNotEmpty ||
        models.any((model) => model.isDownloading || model.isInitializing);
    await WakelockPlus.toggle(enable: keepAwake);
  }

  void _syncTunnelStatusMessage() {
    final server = serverStatus.value;
    final isRunning = server?.isRunning ?? false;
    final tunnelEnabled = server?.tunnelEnabled ?? useTunnel.value;
    final publicUrl = server?.publicUrl ?? '';
    final provider = server?.tunnelProvider ?? tunnelProvider.value;

    if (!isRunning || !tunnelEnabled) {
      tunnelStatusMessage.value = '';
      tunnelStatusIsError.value = false;
      _tunnelRequestedAt = null;
      return;
    }

    if (publicUrl.isNotEmpty) {
      tunnelStatusMessage.value = '';
      tunnelStatusIsError.value = false;
      return;
    }

    _tunnelRequestedAt ??= DateTime.now();
    final elapsed = DateTime.now().difference(_tunnelRequestedAt!);
    if (elapsed < const Duration(seconds: 18)) {
      tunnelStatusIsError.value = false;
      tunnelStatusMessage.value = provider == 'ngrok'
          ? 'Connecting to ngrok...'
          : 'Connecting to Cloudflare...';
      return;
    }

    tunnelStatusIsError.value = true;
    tunnelStatusMessage.value = provider == 'ngrok'
        ? _ngrokTunnelHint()
        : 'Cloudflare tunnel is still not ready. This usually means tunnel startup or DNS failed before a public URL could be published.';
  }

  String _ngrokTunnelHint() {
    final hasReservedDomain = ngrokDomain.value.trim().isNotEmpty;
    final reservedDomainHint = hasReservedDomain
        ? ' Also make sure the ngrok reserved domain really exists in your ngrok dashboard; otherwise leave that field blank.'
        : '';
    return 'ngrok is still not ready. Common causes are a bad authtoken, broken DNS on the phone, or an invalid reserved domain.$reservedDomainHint';
  }

  String _normalizeUrl(String value) {
    if (value.endsWith('/')) {
      return value.substring(0, value.length - 1);
    }
    return value;
  }

  String _friendlyErrorText(Object error) {
    final raw = error.toString().replaceFirst('Exception: ', '').trim();
    final lower = raw.toLowerCase();
    if (lower.contains('hugging face authorization is not configured')) {
      return 'This model needs Hugging Face auth, and OAuth is not configured yet for this app.';
    }
    if (lower.contains('http 401') || lower.contains('http 403')) {
      return 'This model is gated and needs authorization before it can be downloaded.';
    }
    if (lower.contains('selected image could not be prepared')) {
      return 'That image could not be prepared. Try a smaller image or a standard JPG/PNG file.';
    }
    if (lower.contains('selected audio clip could not be prepared')) {
      return 'That audio clip could not be prepared. Try a clean WAV file.';
    }
    if (lower.contains('does not support image input')) {
      return 'The current model does not support image input. Load an image-capable model first.';
    }
    if (lower.contains('does not support audio input')) {
      return 'The current model does not support voice input. Load a voice-capable model first.';
    }
    if (error is DioException) {
      final message = error.message ?? raw;
      if (message.toLowerCase().contains('failed host lookup') &&
          message.toLowerCase().contains('trycloudflare.com')) {
        return 'The tunnel is connected, but this phone cannot resolve the Cloudflare hostname right now. Try the public URL from another device/network, or switch the phone DNS to automatic/1.1.1.1 and test again.';
      }
      return error.message ?? raw;
    }
    if (lower.startsWith('platformexception(')) {
      final start = raw.indexOf(', ');
      final end = raw.lastIndexOf(', null)');
      if (start >= 0 && end > start) {
        return raw.substring(start + 2, end).trim();
      }
    }
    return raw;
  }
}
