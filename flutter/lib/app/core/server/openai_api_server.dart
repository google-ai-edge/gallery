import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../models/native_model_inventory.dart';
import '../native/native_bridge.dart';

class OpenAiApiServer {
  OpenAiApiServer({
    required NativeBridge nativeBridge,
    required Future<NativeModelInventory> Function() inventoryProvider,
  }) : _nativeBridge = nativeBridge,
       _inventoryProvider = inventoryProvider;

  final NativeBridge _nativeBridge;
  final Future<NativeModelInventory> Function() _inventoryProvider;
  final Map<String, _PendingApiChat> _pendingChats =
      <String, _PendingApiChat>{};
  final Set<String> _busyModels = <String>{};
  HttpServer? _server;

  bool get isRunning => _server != null;

  Future<void> start({int port = 8080}) async {
    if (_server != null) {
      return;
    }
    _server = await HttpServer.bind(InternetAddress.anyIPv4, port);
    unawaited(_serve(_server!));
  }

  Future<void> stop() async {
    final server = _server;
    _server = null;
    for (final pending in _pendingChats.values) {
      pending.completeError(StateError('API server stopped.'));
    }
    _pendingChats.clear();
    _busyModels.clear();
    await server?.close(force: true);
  }

  void handleNativeEvent(Map<String, dynamic> event) {
    final type = event['type'] as String? ?? '';
    if (!type.startsWith('api_chat_')) {
      return;
    }
    final requestId = event['requestId'] as String?;
    if (requestId == null) {
      return;
    }
    final pending = _pendingChats[requestId];
    if (pending == null) {
      return;
    }

    switch (type) {
      case 'api_chat_chunk':
        pending.addChunk(event['text'] as String? ?? '');
        if (event['done'] == true) {
          _finishPending(requestId);
        }
        break;
      case 'api_chat_done':
        _finishPending(requestId);
        break;
      case 'api_chat_error':
        final message =
            event['message'] as String? ?? 'Native inference failed.';
        _pendingChats.remove(requestId);
        _busyModels.remove(pending.model);
        pending.completeError(StateError(message));
        break;
    }
  }

  Future<void> _serve(HttpServer server) async {
    await for (final request in server) {
      unawaited(_handleRequest(request));
    }
  }

  Future<void> _handleRequest(HttpRequest request) async {
    _applyCors(request.response);
    if (request.method == 'OPTIONS') {
      request.response.statusCode = HttpStatus.noContent;
      await request.response.close();
      return;
    }

    try {
      final path = request.uri.path;
      if (request.method == 'GET' && path == '/health') {
        await _sendJson(request.response, <String, Object>{'status': 'ok'});
        return;
      }
      if (request.method == 'GET' && path == '/v1/models') {
        await _handleModels(request);
        return;
      }
      if (request.method == 'POST' && path == '/v1/chat/completions') {
        await _handleChatCompletions(request);
        return;
      }
      await _sendJson(request.response, <String, Object>{
        'error': 'Not found',
      }, statusCode: HttpStatus.notFound);
    } catch (error) {
      if (request.response.headers.contentType == ContentType.text) {
        request.response.write(
          'data: {"error": "${_jsonEscape(error.toString())}"}\n\n',
        );
        request.response.write('data: [DONE]\n\n');
        await request.response.close();
        return;
      }
      await _sendJson(request.response, <String, Object>{
        'error': error.toString(),
      }, statusCode: HttpStatus.internalServerError);
    }
  }

  Future<void> _handleModels(HttpRequest request) async {
    final inventory = await _inventoryProvider();
    final created = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final data = inventory.models
        .where((model) => model.isInitialized || model.isActive)
        .map(
          (model) => <String, Object>{
            'id': model.name,
            'object': 'model',
            'created': created,
            'owned_by': 'local',
          },
        )
        .toList(growable: false);
    await _sendJson(request.response, <String, Object>{
      'object': 'list',
      'data': data,
    });
  }

  Future<void> _handleChatCompletions(HttpRequest request) async {
    final payload = await _readJsonObject(request);
    final model = payload['model'] as String?;
    final messages = payload['messages'];
    if (model == null || model.isEmpty) {
      await _sendJson(request.response, <String, Object>{
        'error': 'Missing model.',
      }, statusCode: HttpStatus.badRequest);
      return;
    }
    if (messages is! List || messages.isEmpty) {
      await _sendJson(request.response, <String, Object>{
        'error': 'Missing messages.',
      }, statusCode: HttpStatus.badRequest);
      return;
    }
    if (_busyModels.contains(model)) {
      await _sendJson(request.response, <String, Object>{
        'error': 'Model is busy',
      }, statusCode: HttpStatus.tooManyRequests);
      return;
    }

    final prompt = _composePrompt(messages);
    if (prompt.trim().isEmpty) {
      await _sendJson(request.response, <String, Object>{
        'error': 'Message content is empty.',
      }, statusCode: HttpStatus.badRequest);
      return;
    }

    final stream = payload['stream'] == true;
    if (stream) {
      await _handleStreamingChat(request, model, prompt, payload);
    } else {
      await _handleBlockingChat(request, model, prompt, payload);
    }
  }

  Future<void> _handleBlockingChat(
    HttpRequest request,
    String model,
    String prompt,
    Map<String, dynamic> payload,
  ) async {
    final requestId = _newRequestId();
    final pending = _PendingApiChat(model: model, streaming: false);
    _pendingChats[requestId] = pending;
    _busyModels.add(model);
    try {
      await _nativeBridge.startApiChatCompletion(
        requestId: requestId,
        modelName: model,
        prompt: prompt,
        temperature: _asDouble(payload['temperature']),
        topP: _asDouble(payload['top_p']),
        topK: _asInt(payload['top_k']),
        maxTokens: _asInt(payload['max_tokens']),
      );
      final text = await pending.completed.future;
      await _sendJson(request.response, _chatCompletionResponse(model, text));
    } catch (error) {
      _pendingChats.remove(requestId);
      _busyModels.remove(model);
      await _sendJson(request.response, <String, Object>{
        'error': error.toString(),
      }, statusCode: HttpStatus.internalServerError);
    }
  }

  Future<void> _handleStreamingChat(
    HttpRequest request,
    String model,
    String prompt,
    Map<String, dynamic> payload,
  ) async {
    final requestId = _newRequestId();
    final pending = _PendingApiChat(model: model, streaming: true);
    _pendingChats[requestId] = pending;
    _busyModels.add(model);

    final response = request.response;
    response.statusCode = HttpStatus.ok;
    response.headers.contentType = ContentType(
      'text',
      'event-stream',
      charset: 'utf-8',
    );
    response.headers.set(HttpHeaders.cacheControlHeader, 'no-cache');
    response.headers.set(HttpHeaders.connectionHeader, 'keep-alive');
    _applyCors(response);

    try {
      await _nativeBridge.startApiChatCompletion(
        requestId: requestId,
        modelName: model,
        prompt: prompt,
        temperature: _asDouble(payload['temperature']),
        topP: _asDouble(payload['top_p']),
        topK: _asInt(payload['top_k']),
        maxTokens: _asInt(payload['max_tokens']),
      );
      await for (final chunk in pending.stream.stream) {
        response.write(
          'data: ${jsonEncode(_chatCompletionChunk(model, chunk))}\n\n',
        );
        await response.flush();
      }
      response.write('data: [DONE]\n\n');
      await response.close();
    } catch (error) {
      await _nativeBridge.stopApiChatCompletion(model);
      response.write('data: {"error": "${_jsonEscape(error.toString())}"}\n\n');
      response.write('data: [DONE]\n\n');
      await response.close();
    } finally {
      _pendingChats.remove(requestId);
      _busyModels.remove(model);
    }
  }

  void _finishPending(String requestId) {
    final pending = _pendingChats.remove(requestId);
    if (pending == null) {
      return;
    }
    _busyModels.remove(pending.model);
    pending.complete();
  }

  Future<Map<String, dynamic>> _readJsonObject(HttpRequest request) async {
    final body = await utf8.decoder.bind(request).join();
    final decoded = jsonDecode(body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Request body must be a JSON object.');
    }
    return decoded;
  }

  String _composePrompt(List<dynamic> messages) {
    final lines = <String>[];
    for (final item in messages) {
      if (item is! Map) {
        continue;
      }
      final role = item['role']?.toString().trim();
      final content = item['content'];
      if (role == null || role.isEmpty || content == null) {
        continue;
      }
      final text = content is String ? content : jsonEncode(content);
      if (text.trim().isEmpty) {
        continue;
      }
      lines.add('${role.toUpperCase()}: ${text.trim()}');
    }
    lines.add('ASSISTANT:');
    return lines.join('\n\n');
  }

  Map<String, Object> _chatCompletionResponse(String model, String text) {
    return <String, Object>{
      'id': _newCompletionId(),
      'object': 'chat.completion',
      'created': DateTime.now().millisecondsSinceEpoch ~/ 1000,
      'model': model,
      'choices': <Object>[
        <String, Object?>{
          'index': 0,
          'message': <String, Object>{'role': 'assistant', 'content': text},
          'finish_reason': 'stop',
        },
      ],
    };
  }

  Map<String, Object> _chatCompletionChunk(String model, String text) {
    return <String, Object>{
      'id': _newCompletionId(),
      'object': 'chat.completion.chunk',
      'created': DateTime.now().millisecondsSinceEpoch ~/ 1000,
      'model': model,
      'choices': <Object>[
        <String, Object?>{
          'index': 0,
          'delta': <String, Object>{'content': text},
          'finish_reason': null,
        },
      ],
    };
  }

  Future<void> _sendJson(
    HttpResponse response,
    Object payload, {
    int statusCode = HttpStatus.ok,
  }) async {
    response.statusCode = statusCode;
    response.headers.contentType = ContentType.json;
    _applyCors(response);
    response.write(jsonEncode(payload));
    await response.close();
  }

  void _applyCors(HttpResponse response) {
    response.headers.set(HttpHeaders.accessControlAllowOriginHeader, '*');
    response.headers.set(
      HttpHeaders.accessControlAllowMethodsHeader,
      'GET,POST,OPTIONS',
    );
    response.headers.set(
      HttpHeaders.accessControlAllowHeadersHeader,
      'Authorization,Content-Type',
    );
  }

  String _newRequestId() => DateTime.now().microsecondsSinceEpoch.toString();

  String _newCompletionId() =>
      'chatcmpl-${DateTime.now().microsecondsSinceEpoch}';

  double? _asDouble(Object? value) => value is num ? value.toDouble() : null;

  int? _asInt(Object? value) => value is num ? value.toInt() : null;

  String _jsonEscape(String value) =>
      value.replaceAll(r'\', r'\\').replaceAll('"', r'\"');
}

class _PendingApiChat {
  _PendingApiChat({required this.model, required this.streaming});

  final String model;
  final bool streaming;
  final StringBuffer buffer = StringBuffer();
  final Completer<String> completed = Completer<String>();
  final StreamController<String> stream = StreamController<String>();

  void addChunk(String text) {
    if (text.isEmpty) {
      return;
    }
    buffer.write(text);
    if (streaming && !stream.isClosed) {
      stream.add(text);
    }
  }

  void complete() {
    if (!completed.isCompleted) {
      completed.complete(buffer.toString());
    }
    if (!stream.isClosed) {
      unawaited(stream.close());
    }
  }

  void completeError(Object error) {
    if (!completed.isCompleted) {
      completed.completeError(error);
    }
    if (!stream.isClosed) {
      stream.addError(error);
      unawaited(stream.close());
    }
  }
}
