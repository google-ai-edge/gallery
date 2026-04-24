class ChatMessageItem {
  ChatMessageItem({
    required this.id,
    required this.role,
    required this.text,
    this.thinking = '',
    this.imagePaths = const <String>[],
    this.audioPaths = const <String>[],
    this.documentName,
    this.isStreaming = false,
    this.isError = false,
  });

  final String id;
  final String role;
  final String text;
  final String thinking;
  final List<String> imagePaths;
  final List<String> audioPaths;
  final String? documentName;
  final bool isStreaming;
  final bool isError;

  factory ChatMessageItem.fromMap(Map<String, dynamic> map) {
    return ChatMessageItem(
      id: map['id'] as String? ?? '',
      role: map['role'] as String? ?? 'assistant',
      text: map['text'] as String? ?? '',
      thinking: map['thinking'] as String? ?? '',
      imagePaths: (map['imagePaths'] as List<dynamic>? ?? const <dynamic>[])
          .whereType<String>()
          .toList(growable: false),
      audioPaths: (map['audioPaths'] as List<dynamic>? ?? const <dynamic>[])
          .whereType<String>()
          .toList(growable: false),
      documentName: map['documentName'] as String?,
      isStreaming: map['isStreaming'] as bool? ?? false,
      isError: map['isError'] as bool? ?? false,
    );
  }

  ChatMessageItem copyWith({
    String? text,
    String? thinking,
    bool? isStreaming,
    bool? isError,
  }) {
    return ChatMessageItem(
      id: id,
      role: role,
      text: text ?? this.text,
      thinking: thinking ?? this.thinking,
      imagePaths: imagePaths,
      audioPaths: audioPaths,
      documentName: documentName,
      isStreaming: isStreaming ?? this.isStreaming,
      isError: isError ?? this.isError,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'role': role,
      'text': text,
      'thinking': thinking,
      'imagePaths': imagePaths,
      'audioPaths': audioPaths,
      'documentName': documentName,
      'isStreaming': isStreaming,
      'isError': isError,
    };
  }
}
