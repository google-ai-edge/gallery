class NativeCapabilities {
  NativeCapabilities({
    required this.camera,
    required this.microphone,
    required this.gpuAcceleration,
    required this.nnapi,
    required this.foregroundService,
    required this.localServerHooks,
    required this.notes,
  });

  final bool camera;
  final bool microphone;
  final bool gpuAcceleration;
  final bool nnapi;
  final bool foregroundService;
  final bool localServerHooks;
  final String notes;

  factory NativeCapabilities.fromMap(Map<String, dynamic> map) {
    return NativeCapabilities(
      camera: map['camera'] as bool? ?? false,
      microphone: map['microphone'] as bool? ?? false,
      gpuAcceleration: map['gpuAcceleration'] as bool? ?? false,
      nnapi: map['nnapi'] as bool? ?? false,
      foregroundService: map['foregroundService'] as bool? ?? false,
      localServerHooks: map['localServerHooks'] as bool? ?? false,
      notes: map['notes'] as String? ?? '',
    );
  }
}
