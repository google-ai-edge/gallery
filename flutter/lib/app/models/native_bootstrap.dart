class NativeBootstrap {
  NativeBootstrap({
    required this.platform,
    required this.release,
    required this.sdkInt,
    required this.manufacturer,
    required this.model,
    required this.appVersion,
    required this.bridgeVersion,
    required this.abis,
  });

  final String platform;
  final String release;
  final int sdkInt;
  final String manufacturer;
  final String model;
  final String appVersion;
  final String bridgeVersion;
  final List<String> abis;

  factory NativeBootstrap.fromMap(Map<String, dynamic> map) {
    return NativeBootstrap(
      platform: map['platform'] as String? ?? 'Android',
      release: map['release'] as String? ?? 'Unknown',
      sdkInt: (map['sdkInt'] as num?)?.toInt() ?? 0,
      manufacturer: map['manufacturer'] as String? ?? 'Unknown',
      model: map['model'] as String? ?? 'Unknown',
      appVersion: map['appVersion'] as String? ?? '0.0.0',
      bridgeVersion: map['bridgeVersion'] as String? ?? '0.1.0',
      abis: (map['abis'] as List<dynamic>? ?? const <dynamic>[])
          .map((item) => item.toString())
          .toList(),
    );
  }
}
