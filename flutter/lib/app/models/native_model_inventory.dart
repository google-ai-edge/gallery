import 'native_model_summary.dart';

class NativeModelInventory {
  NativeModelInventory({
    required this.isLoading,
    required this.error,
    required this.count,
    required this.models,
    required this.activeModelName,
    required this.operationStatus,
    required this.operationMessage,
  });

  final bool isLoading;
  final String error;
  final int count;
  final List<NativeModelSummary> models;
  final String? activeModelName;
  final String operationStatus;
  final String operationMessage;

  factory NativeModelInventory.fromMap(Map<String, dynamic> map) {
    final rawModels = map['models'] as List<dynamic>? ?? const <dynamic>[];

    return NativeModelInventory(
      isLoading: map['isLoading'] as bool? ?? false,
      error: map['error'] as String? ?? '',
      count: (map['count'] as num?)?.toInt() ?? rawModels.length,
      activeModelName: map['activeModelName'] as String?,
      operationStatus: map['operationStatus'] as String? ?? 'idle',
      operationMessage: map['operationMessage'] as String? ?? '',
      models: rawModels
          .whereType<Map<dynamic, dynamic>>()
          .map(
            (item) => NativeModelSummary.fromMap(
              item.map((key, value) => MapEntry(key.toString(), value)),
            ),
          )
          .toList(),
    );
  }
}
