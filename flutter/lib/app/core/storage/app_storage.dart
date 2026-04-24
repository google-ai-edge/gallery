import 'dart:convert';

import 'package:hive_flutter/hive_flutter.dart';

class AppStorage {
  AppStorage._();

  static const String _defaultHfToken = '';

  static const String _boxName = 'edge_gallery_flutter';
  static const String _customModelsKey = 'custom_models';
  static const String _serverUrlKey = 'server_url';
  static const String _serverTunnelKey = 'server_tunnel';
  static const String _serverTunnelProviderKey = 'server_tunnel_provider';
  static const String _lastRefreshAtKey = 'last_refresh_at';
  static const String _chatSessionsKey = 'chat_sessions';
  static const String _activeChatIdKey = 'active_chat_id';
  static const String _systemPromptKey = 'system_prompt';
  static const String _temperatureKey = 'temperature';
  static const String _maxTokensKey = 'max_tokens';
  static const String _hfTokenKey = 'hf_token';
  static const String _cloudflareTunnelTokenKey = 'cloudflare_tunnel_token';
  static const String _cloudflarePublicUrlKey = 'cloudflare_public_url';
  static const String _ngrokAuthTokenKey = 'ngrok_auth_token';
  static const String _ngrokDomainKey = 'ngrok_domain';

  static final AppStorage instance = AppStorage._();

  static late Box<dynamic> _box;

  static Future<void> init() async {
    await Hive.initFlutter();
    _box = await Hive.openBox<dynamic>(_boxName);
  }

  String? get serverUrl => _box.get(_serverUrlKey) as String?;

  set serverUrl(String? value) {
    if (value == null || value.isEmpty) {
      _box.delete(_serverUrlKey);
      return;
    }

    _box.put(_serverUrlKey, value);
  }

  bool get serverTunnelEnabled =>
      _box.get(_serverTunnelKey, defaultValue: true) as bool;

  set serverTunnelEnabled(bool value) {
    _box.put(_serverTunnelKey, value);
  }

  String get serverTunnelProvider =>
      (_box.get(_serverTunnelProviderKey) as String?) ?? 'cloudflare';

  set serverTunnelProvider(String value) {
    _box.put(
      _serverTunnelProviderKey,
      value.trim() == 'ngrok' ? 'ngrok' : 'cloudflare',
    );
  }

  List<Map<String, dynamic>> get customModels {
    final rawList =
        (_box.get(_customModelsKey) as List<dynamic>?) ?? const <dynamic>[];
    return rawList
        .whereType<String>()
        .map((item) => jsonDecode(item) as Map<String, dynamic>)
        .toList();
  }

  Future<void> upsertCustomModel(Map<String, dynamic> model) async {
    final items = customModels;
    final index = items.indexWhere((item) => item['name'] == model['name']);
    if (index >= 0) {
      items[index] = model;
    } else {
      items.add(model);
    }
    await _box.put(
      _customModelsKey,
      items.map(jsonEncode).toList(growable: false),
    );
  }

  Future<void> removeCustomModel(String name) async {
    final items = customModels.where((item) => item['name'] != name).toList();
    await _box.put(
      _customModelsKey,
      items.map(jsonEncode).toList(growable: false),
    );
  }

  List<Map<String, dynamic>> get chatSessions {
    final rawList =
        (_box.get(_chatSessionsKey) as List<dynamic>?) ?? const <dynamic>[];
    return rawList
        .whereType<String>()
        .map((item) => jsonDecode(item) as Map<String, dynamic>)
        .toList();
  }

  Future<void> saveChatSessions(List<Map<String, dynamic>> sessions) async {
    await _box.put(
      _chatSessionsKey,
      sessions.map(jsonEncode).toList(growable: false),
    );
  }

  String? get activeChatId => _box.get(_activeChatIdKey) as String?;

  set activeChatId(String? value) {
    if (value == null || value.isEmpty) {
      _box.delete(_activeChatIdKey);
      return;
    }
    _box.put(_activeChatIdKey, value);
  }

  String get systemPrompt => (_box.get(_systemPromptKey) as String?) ?? '';

  set systemPrompt(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_systemPromptKey);
      return;
    }
    _box.put(_systemPromptKey, value);
  }

  double get temperature =>
      (_box.get(_temperatureKey, defaultValue: 1.0) as num).toDouble();

  set temperature(double value) {
    _box.put(_temperatureKey, value);
  }

  int get maxTokens =>
      (_box.get(_maxTokensKey, defaultValue: 2048) as num).toInt();

  set maxTokens(int value) {
    _box.put(_maxTokensKey, value);
  }

  String get huggingFaceToken =>
      (_box.get(_hfTokenKey) as String?) ?? _defaultHfToken;

  set huggingFaceToken(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_hfTokenKey);
      return;
    }
    _box.put(_hfTokenKey, value);
  }

  String get cloudflareTunnelToken =>
      (_box.get(_cloudflareTunnelTokenKey) as String?) ?? '';

  set cloudflareTunnelToken(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_cloudflareTunnelTokenKey);
      return;
    }
    _box.put(_cloudflareTunnelTokenKey, value.trim());
  }

  String get cloudflarePublicUrl =>
      (_box.get(_cloudflarePublicUrlKey) as String?) ?? '';

  set cloudflarePublicUrl(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_cloudflarePublicUrlKey);
      return;
    }
    _box.put(_cloudflarePublicUrlKey, value.trim());
  }

  String get ngrokAuthToken => (_box.get(_ngrokAuthTokenKey) as String?) ?? '';

  set ngrokAuthToken(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_ngrokAuthTokenKey);
      return;
    }
    _box.put(_ngrokAuthTokenKey, value.trim());
  }

  String get ngrokDomain => (_box.get(_ngrokDomainKey) as String?) ?? '';

  set ngrokDomain(String value) {
    if (value.trim().isEmpty) {
      _box.delete(_ngrokDomainKey);
      return;
    }
    _box.put(_ngrokDomainKey, value.trim());
  }

  DateTime? get lastRefreshAt {
    final value = _box.get(_lastRefreshAtKey);
    if (value is String) {
      return DateTime.tryParse(value);
    }

    return null;
  }

  set lastRefreshAt(DateTime? value) {
    if (value == null) {
      _box.delete(_lastRefreshAtKey);
      return;
    }

    _box.put(_lastRefreshAtKey, value.toIso8601String());
  }
}
