class NativeModelSummary {
  NativeModelSummary({
    required this.name,
    required this.displayName,
    required this.runtimeLabel,
    required this.downloadStatus,
    required this.downloadedBytes,
    required this.totalBytes,
    required this.downloadError,
    required this.bytesPerSecond,
    required this.remainingMs,
    required this.initializationStatus,
    required this.initializationError,
    required this.isInitialized,
    required this.isInitializing,
    required this.supportsImage,
    required this.supportsAudio,
    required this.supportsThinking,
    required this.supportsMobileActions,
    required this.imported,
    required this.isCustomRemote,
    required this.isActive,
    required this.sizeInBytes,
    required this.minRamGb,
    required this.url,
  });

  final String name;
  final String displayName;
  final String runtimeLabel;
  final String downloadStatus;
  final int downloadedBytes;
  final int totalBytes;
  final String downloadError;
  final int bytesPerSecond;
  final int remainingMs;
  final String initializationStatus;
  final String initializationError;
  final bool isInitialized;
  final bool isInitializing;
  final bool supportsImage;
  final bool supportsAudio;
  final bool supportsThinking;
  final bool supportsMobileActions;
  final bool imported;
  final bool isCustomRemote;
  final bool isActive;
  final int sizeInBytes;
  final int? minRamGb;
  final String url;

  bool get isDownloaded => downloadStatus == 'SUCCEEDED';
  bool get isDownloading =>
      downloadStatus == 'IN_PROGRESS' || downloadStatus == 'UNZIPPING';
  double get progress {
    if (totalBytes <= 0) {
      return isDownloaded ? 1 : 0;
    }
    return (downloadedBytes / totalBytes).clamp(0, 1);
  }

  factory NativeModelSummary.fromMap(Map<String, dynamic> map) {
    return NativeModelSummary(
      name: map['name'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      runtimeLabel: map['runtimeType'] as String? ?? '',
      downloadStatus: map['downloadStatus'] as String? ?? '',
      downloadedBytes: (map['downloadedBytes'] as num?)?.toInt() ?? 0,
      totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
      downloadError: map['downloadError'] as String? ?? '',
      bytesPerSecond: (map['bytesPerSecond'] as num?)?.toInt() ?? 0,
      remainingMs: (map['remainingMs'] as num?)?.toInt() ?? 0,
      initializationStatus: map['initializationStatus'] as String? ?? '',
      initializationError: map['initializationError'] as String? ?? '',
      isInitialized: map['isInitialized'] as bool? ?? false,
      isInitializing: map['isInitializing'] as bool? ?? false,
      supportsImage: map['supportsImage'] as bool? ?? false,
      supportsAudio: map['supportsAudio'] as bool? ?? false,
      supportsThinking: map['supportsThinking'] as bool? ?? false,
      supportsMobileActions: map['supportsMobileActions'] as bool? ?? false,
      imported: map['imported'] as bool? ?? false,
      isCustomRemote: map['isCustomRemote'] as bool? ?? false,
      isActive: map['isActive'] as bool? ?? false,
      sizeInBytes: (map['sizeInBytes'] as num?)?.toInt() ?? 0,
      minRamGb: (map['minRamGb'] as num?)?.toInt(),
      url: map['url'] as String? ?? '',
    );
  }
}
