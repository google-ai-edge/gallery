import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

class HttpClient {
  HttpClient._(this.dio);

  final Dio dio;

  static Future<HttpClient> create() async {
    final dio = Dio(
      BaseOptions(
        connectTimeout: const Duration(seconds: 12),
        receiveTimeout: const Duration(seconds: 20),
        sendTimeout: const Duration(seconds: 20),
        headers: const {
          'Accept': 'application/json, text/plain, */*',
          'User-Agent': 'edge-gallery-flutter-shell',
        },
      ),
    );

    dio.interceptors.add(
      InterceptorsWrapper(
        onError: (error, handler) {
          debugPrint('HTTP error: ${error.message}');
          handler.next(error);
        },
      ),
    );

    return HttpClient._(dio);
  }
}
