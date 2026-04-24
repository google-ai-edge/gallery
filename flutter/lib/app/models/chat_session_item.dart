import 'chat_message_item.dart';

class ChatSessionItem {
  ChatSessionItem({
    required this.id,
    required this.title,
    required this.updatedAt,
    required this.messages,
    this.modelName,
  });

  final String id;
  final String title;
  final DateTime updatedAt;
  final List<ChatMessageItem> messages;
  final String? modelName;

  factory ChatSessionItem.fromMap(Map<String, dynamic> map) {
    return ChatSessionItem(
      id: map['id'] as String? ?? '',
      title: map['title'] as String? ?? 'Untitled chat',
      updatedAt:
          DateTime.tryParse(map['updatedAt'] as String? ?? '') ??
          DateTime.now(),
      messages: (map['messages'] as List<dynamic>? ?? const <dynamic>[])
          .whereType<Map<dynamic, dynamic>>()
          .map(
            (item) => ChatMessageItem.fromMap(
              item.map((key, value) => MapEntry(key.toString(), value)),
            ),
          )
          .toList(growable: false),
      modelName: map['modelName'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'title': title,
      'updatedAt': updatedAt.toIso8601String(),
      'messages': messages.map((message) => message.toMap()).toList(),
      'modelName': modelName,
    };
  }
}
