import 'package:flutter/services.dart';

import '../../models/native_model_inventory.dart';
import '../../models/native_server_status.dart';
import '../../models/native_bootstrap.dart';
import '../../models/native_capabilities.dart';

class NativeBridge {
  static const MethodChannel _channel = MethodChannel(
    'edge_gallery_flutter/native_bridge',
  );
  static const EventChannel _events = EventChannel(
    'edge_gallery_flutter/native_events',
  );

  Stream<Map<String, dynamic>> get events => _events
      .receiveBroadcastStream()
      .where((event) => event is Map<dynamic, dynamic>)
      .cast<Map<dynamic, dynamic>>()
      .map(
        (event) => event.map((key, value) => MapEntry(key.toString(), value)),
      );

  Future<NativeBootstrap> fetchBootstrap() async {
    final map = await _channel.invokeMapMethod<String, dynamic>(
      'getNativeBootstrap',
    );

    if (map == null) {
      throw StateError('Native bootstrap payload was empty.');
    }

    return NativeBootstrap.fromMap(map);
  }

  Future<NativeCapabilities> fetchCapabilities() async {
    final map = await _channel.invokeMapMethod<String, dynamic>(
      'getNativeCapabilities',
    );

    if (map == null) {
      throw StateError('Native capabilities payload was empty.');
    }

    return NativeCapabilities.fromMap(map);
  }

  Future<String> pingRuntime() async {
    final result = await _channel.invokeMethod<String>('pingNativeRuntime');
    return result ?? 'Native runtime responded without a message.';
  }

  Future<NativeServerStatus> fetchServerStatus() async {
    final map = await _channel.invokeMapMethod<String, dynamic>(
      'getServerStatus',
    );
    if (map == null) {
      throw StateError('Native server status payload was empty.');
    }
    return NativeServerStatus.fromMap(map);
  }

  Future<NativeModelInventory> fetchModelInventory() async {
    final map = await _channel.invokeMapMethod<String, dynamic>(
      'getModelInventory',
    );
    if (map == null) {
      throw StateError('Native model inventory payload was empty.');
    }
    return NativeModelInventory.fromMap(map);
  }

  Future<void> startNativeServer({required bool useTunnel}) async {
    await _channel.invokeMethod<void>('startNativeServer', <String, dynamic>{
      'useTunnel': useTunnel,
    });
  }

  Future<void> stopNativeServer() async {
    await _channel.invokeMethod<void>('stopNativeServer');
  }

  Future<void> downloadModel(String modelName) async {
    await _channel.invokeMethod<void>('downloadModel', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> cancelDownloadModel(String modelName) async {
    await _channel.invokeMethod<void>('cancelDownloadModel', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> deleteModel(String modelName) async {
    await _channel.invokeMethod<void>('deleteModel', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> loadModel(String modelName) async {
    await _channel.invokeMethod<void>('loadModel', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> unloadModel(String modelName) async {
    await _channel.invokeMethod<void>('unloadModel', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> resetConversation(String modelName) async {
    await _channel.invokeMethod<void>('resetConversation', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> stopChatGeneration(String modelName) async {
    await _channel.invokeMethod<void>('stopChatGeneration', <String, dynamic>{
      'modelName': modelName,
    });
  }

  Future<void> sendChatMessage({
    required String modelName,
    required String prompt,
    String? documentContext,
    List<String> imagePaths = const <String>[],
    List<String> audioPaths = const <String>[],
    String? systemPrompt,
    double? temperature,
    int? maxTokens,
  }) async {
    await _channel.invokeMethod<void>('sendChatMessage', <String, dynamic>{
      'modelName': modelName,
      'prompt': prompt,
      'documentContext': documentContext,
      'imagePaths': imagePaths,
      'audioPaths': audioPaths,
      'systemPrompt': systemPrompt,
      'temperature': temperature,
      'maxTokens': maxTokens,
    });
  }

  Future<void> saveAccessToken(String accessToken) async {
    await _channel.invokeMethod<void>('saveAccessToken', <String, dynamic>{
      'accessToken': accessToken,
    });
  }

  Future<void> clearAccessToken() async {
    await _channel.invokeMethod<void>('clearAccessToken');
  }

  Future<void> importLocalModel({
    required String filePath,
    required String fileName,
    required int defaultMaxTokens,
    required int defaultTopK,
    required double defaultTopP,
    required double defaultTemperature,
    required bool supportImage,
    required bool supportAudio,
    required bool supportTinyGarden,
    required bool supportMobileActions,
    required bool supportThinking,
    required List<String> accelerators,
  }) async {
    await _channel.invokeMethod<void>('importLocalModel', <String, dynamic>{
      'filePath': filePath,
      'fileName': fileName,
      'defaultMaxTokens': defaultMaxTokens,
      'defaultTopK': defaultTopK,
      'defaultTopP': defaultTopP,
      'defaultTemperature': defaultTemperature,
      'supportImage': supportImage,
      'supportAudio': supportAudio,
      'supportTinyGarden': supportTinyGarden,
      'supportMobileActions': supportMobileActions,
      'supportThinking': supportThinking,
      'accelerators': accelerators,
    });
  }

  Future<void> registerCustomRemoteModel({
    required String name,
    required String url,
    required String fileName,
    required int sizeInBytes,
    required int? minRamGb,
    required int defaultMaxTokens,
    required int defaultTopK,
    required double defaultTopP,
    required double defaultTemperature,
    required bool supportImage,
    required bool supportAudio,
    required bool supportTinyGarden,
    required bool supportMobileActions,
    required bool supportThinking,
    required List<String> accelerators,
  }) async {
    await _channel
        .invokeMethod<void>('registerCustomRemoteModel', <String, dynamic>{
          'name': name,
          'url': url,
          'fileName': fileName,
          'sizeInBytes': sizeInBytes,
          'minRamGb': minRamGb,
          'defaultMaxTokens': defaultMaxTokens,
          'defaultTopK': defaultTopK,
          'defaultTopP': defaultTopP,
          'defaultTemperature': defaultTemperature,
          'supportImage': supportImage,
          'supportAudio': supportAudio,
          'supportTinyGarden': supportTinyGarden,
          'supportMobileActions': supportMobileActions,
          'supportThinking': supportThinking,
          'accelerators': accelerators,
        });
  }
}
