import 'package:edge_gallery_flutter/app/models/native_bootstrap.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parses native bootstrap payload', () {
    final bootstrap = NativeBootstrap.fromMap(const <String, dynamic>{
      'platform': 'Android',
      'release': '16',
      'sdkInt': 36,
      'manufacturer': 'Google',
      'model': 'Pixel',
      'appVersion': '1.0.0',
      'bridgeVersion': '0.1.0',
      'abis': <String>['arm64-v8a'],
    });

    expect(bootstrap.platform, 'Android');
    expect(bootstrap.sdkInt, 36);
    expect(bootstrap.abis, <String>['arm64-v8a']);
  });
}
