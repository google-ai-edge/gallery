class NativeServerStatus {
  NativeServerStatus({
    required this.isRunning,
    required this.localUrl,
    required this.publicUrl,
    required this.tunnelEnabled,
    required this.tunnelProvider,
    required this.loadingModels,
    required this.modelError,
    required this.activeModelName,
    required this.operationStatus,
    required this.operationMessage,
  });

  final bool isRunning;
  final String? localUrl;
  final String? publicUrl;
  final bool tunnelEnabled;
  final String tunnelProvider;
  final bool loadingModels;
  final String modelError;
  final String? activeModelName;
  final String operationStatus;
  final String operationMessage;

  factory NativeServerStatus.fromMap(Map<String, dynamic> map) {
    return NativeServerStatus(
      isRunning: map['isRunning'] as bool? ?? false,
      localUrl: map['localUrl'] as String?,
      publicUrl: map['publicUrl'] as String?,
      tunnelEnabled: map['tunnelEnabled'] as bool? ?? false,
      tunnelProvider: map['tunnelProvider'] as String? ?? 'cloudflare',
      loadingModels: map['loadingModels'] as bool? ?? false,
      modelError: map['modelError'] as String? ?? '',
      activeModelName: map['activeModelName'] as String?,
      operationStatus: map['operationStatus'] as String? ?? 'idle',
      operationMessage: map['operationMessage'] as String? ?? '',
    );
  }
}
