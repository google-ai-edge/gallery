# Neural Forge Architecture

Neural Forge is a comprehensive mobile AI platform built on the foundation of Google's AI Edge Gallery, extended with universal model format support, intelligent download management, and a vision for advanced on-device AI capabilities.

## 🎯 Vision

Transform Android devices into portable AI powerhouses that can:
- Load and run models in multiple formats (TFLite, ONNX, PyTorch, LiteRT-LM)
- Download large models efficiently with resume capability
- Convert between model formats on-device
- Optimize models for mobile hardware
- Chain models together for complex workflows
- Share models peer-to-peer without internet
- Fine-tune models on-device

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Neural Forge Engine                       │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           Model Registry & Orchestration              │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │              │              │              │
         ▼              ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  Download    │ │   Format     │ │  Conversion  │ │  Execution   │
│  Manager     │ │  Detection   │ │  Pipeline    │ │  Engines     │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
     │                   │              │              │
     │                   │              │              ├─ ONNX Runtime
     │                   │              │              ├─ TFLite
     │                   │              │              └─ LiteRT-LM
     ▼                   ▼              ▼              ▼
┌──────────────────────────────────────────────────────────────┐
│                    Device Hardware                            │
│  CPU | GPU (Adreno) | NPU | DSP (Hexagon) | NNAPI           │
└──────────────────────────────────────────────────────────────┘
```

## 📦 Core Components

### 1. Neural Forge Engine (`NeuralForgeEngine.kt`)

The central orchestrator that manages all model operations.

**Responsibilities:**
- Model lifecycle management (load, unload, registry)
- Coordination between download, conversion, and execution
- Device capability detection
- Resource management

**Key APIs:**
```kotlin
// Download a model
val downloadFlow = engine.downloadModel(url, modelName)

// Load a model
val result = engine.loadModel(modelId, modelName, OptimizationPreset.BALANCED)

// Get device capabilities
val capabilities = engine.getDeviceCapabilities()
```

### 2. Model Download Manager (`ModelDownloadManager.kt`)

Enhanced download system with enterprise-grade features.

**Features:**
- ✅ Chunked downloads for large models
- ✅ Resume capability (HTTP Range requests)
- ✅ Automatic retry with exponential backoff
- ✅ Real-time progress tracking (MB/s, ETA)
- ✅ Split model support (merge multiple parts)
- ✅ Concurrent download management

**Architecture:**
```kotlin
ModelDownloadManager
├── OkHttpClient (with custom interceptors)
│   ├── ProgressInterceptor      // Track download progress
│   ├── RetryInterceptor         // Auto-retry on failures
│   └── ConnectionPool           // Efficient connection reuse
└── Download State Management
    ├── Preparing
    ├── Downloading(progress)
    ├── Merging (for split models)
    ├── Completed(file)
    └── Failed(error)
```

**Usage Example:**
```kotlin
downloadManager.downloadModel(url, "model.onnx", enableResume = true)
    .collect { state ->
        when (state) {
            is DownloadState.Downloading -> {
                val progress = state.progress
                println("${progress.progressPercent}% @ ${progress.downloadRate} MB/s")
            }
            is DownloadState.Completed -> {
                println("Downloaded to: ${state.file}")
            }
        }
    }
```

### 3. Model Format Detection (`ModelFormat.kt`, `ModelFormatDetector.kt`)

Intelligent format detection system.

**Supported Formats:**
- **TensorFlow Lite** (.tflite) - Magic bytes: `TFL3`
- **ONNX** (.onnx) - Protocol Buffers format
- **PyTorch Mobile** (.pt, .pth) - ZIP-based format
- **LiteRT-LM** (.litertlm) - Google's optimized LLM format

**Detection Strategy:**
1. File extension check (fast path)
2. Magic byte analysis (fallback)
3. Format validation

```kotlin
val detector = ModelFormatDetector()
val format = detector.detectFormat(modelFile)

when (format) {
    ModelFormat.TensorFlowLite -> // Load with TFLite
    ModelFormat.ONNX -> // Load with ONNX Runtime
    ModelFormat.LiteRTLM -> // Load with LiteRT
}
```

### 4. ONNX Inference Engine (`ONNXInferenceEngine.kt`)

Full ONNX Runtime integration for Android.

**Features:**
- ✅ Multi-threaded CPU inference
- ✅ Graph optimization (all optimization levels)
- ✅ Session management
- ✅ Input/output introspection
- 🔄 GPU acceleration (Phase 2)
- 🔄 NNAPI delegation (Phase 2)

**Architecture:**
```kotlin
ONNXInferenceEngine
├── OrtEnvironment (singleton)
├── SessionOptions
│   ├── Thread configuration
│   ├── Optimization level
│   └── Execution providers
└── Session Management
    ├── Load model
    ├── Run inference
    ├── Multi-input support
    └── Resource cleanup
```

**Example:**
```kotlin
// Load ONNX model
val sessionResult = onnxEngine.loadModel(modelFile)

// Run inference
val outputResult = onnxEngine.runInference(
    session = sessionResult.getOrThrow(),
    inputData = floatArray,
    inputShape = longArrayOf(1, 3, 224, 224)
)
```

### 5. Model Converter (`ModelConverter.kt`)

Model format conversion and optimization pipeline.

**Phase 1 (Current):** Foundation infrastructure
**Phase 2 (Planned):** Full conversion implementations

**Planned Conversions:**
- ONNX → TensorFlow Lite
- PyTorch → ONNX → TensorFlow Lite
- Quantization (FP32 → FP16 → INT8 → INT4)
- Pruning and layer fusion
- Mobile-specific optimizations

**Optimization Presets:**
```kotlin
enum class OptimizationPreset {
    SPEED,      // Maximize FPS - aggressive quantization
    BALANCED,   // Balance speed/accuracy - selective quantization
    QUALITY,    // Preserve accuracy - minimal optimization
    MEMORY      // Minimize RAM - compression + quantization
}
```

## 🔄 Data Flow

### Model Download & Load Flow

```
User Action → downloadModel()
                    ↓
          [Download Manager]
            ↓ HTTP Request
          [OkHttp Client]
            ↓ Stream data
        [Progress Updates] → UI
            ↓
       [Save to Storage]
            ↓
    DownloadState.Completed
            ↓
         loadModel()
            ↓
      [Format Detector]
            ↓
    [Model Converter] (optimize)
            ↓
      [Inference Engine]
            ↓
     [Model Registry]
            ↓
         Ready!
```

### Inference Flow

```
User Input → prepareInput()
                 ↓
         [Model Wrapper]
                 ↓
         Select Engine
         ┌───────┴────────┐
         ▼                ▼
    [ONNX Engine]   [TFLite Engine]
         │                │
         ▼                ▼
     run inference    run inference
         │                │
         ▼                ▼
    [Process Output]  [Process Output]
         │                │
         └────────┬────────┘
                  ▼
            Return Result → UI
```

## 🎨 UI Architecture (Existing)

Neural Forge inherits a sophisticated Jetpack Compose UI from Edge Gallery:

- **HomeScreen**: Model gallery with categories
- **ChatView**: Multi-turn conversation interface
- **ModelPicker**: Model selection and management
- **DownloadPanel**: Progress tracking and controls

## 🚀 Performance Optimizations

### Download Performance
- **Chunked Downloads**: 10MB chunks for efficient mobile downloads
- **Resume Support**: Continue interrupted downloads
- **Retry Logic**: Exponential backoff (2s, 4s, 8s, 16s)
- **Connection Pooling**: Reuse connections for efficiency

### Inference Performance
- **Multi-threading**: Use all available CPU cores
- **Graph Optimization**: ONNX graph optimizations
- **Memory Mapping**: Efficient large model loading (planned)
- **Hardware Acceleration**: GPU/NPU/DSP support (planned)

### Memory Management
- **Lazy Loading**: Load models on-demand
- **Resource Cleanup**: Automatic session closure
- **Memory Monitoring**: Track usage and available memory

## 📊 Device Capabilities

Neural Forge detects and utilizes device hardware:

```kotlin
data class DeviceCapabilities(
    val cpuCores: Int,              // e.g., 8 cores on Snapdragon 8 Gen 2
    val totalMemory: Long,          // Total RAM
    val availableMemory: Long,      // Available RAM
    val supportedAccelerators: Set<Accelerator>
)

enum class Accelerator {
    CPU,      // Always available
    GPU,      // Adreno 740 on S23 Ultra
    NPU,      // Neural Processing Unit
    DSP,      // Hexagon DSP on Snapdragon
    NNAPI     // Android Neural Networks API
}
```

## 🔐 Security & Privacy

- **Local Processing**: All inference happens on-device
- **No Telemetry**: No model usage tracking (optional analytics)
- **Secure Storage**: Models stored in app-private directory
- **Permission Model**: Minimal permissions required

## 📱 Hardware Optimization (S23 Ultra)

For Snapdragon 8 Gen 2 devices like S23 Ultra:

- **Adreno 740 GPU**: Graphics-optimized operations
- **Hexagon DSP**: Efficient neural network operations
- **Kryo CPU**: 8-core configuration
- **12GB RAM**: Large model support

## 🛣️ Roadmap

### Phase 1 (✅ Complete)
- [x] Universal format support (TFLite, ONNX, LiteRT-LM)
- [x] Enhanced download manager
- [x] Format detection
- [x] ONNX Runtime integration
- [x] Core architecture

### Phase 2 (In Progress)
- [ ] Model format conversion
- [ ] Advanced UI with Lottie animations
- [ ] GPU acceleration for ONNX
- [ ] Quantization pipeline
- [ ] Split model downloads
- [ ] Model marketplace UI

### Phase 3 (Planned)
- [ ] Model chaining/pipelines
- [ ] P2P model sharing
- [ ] On-device fine-tuning (LoRA)
- [ ] Voice commands
- [ ] Battery-aware scheduling
- [ ] AR model visualization

## 🤝 Contributing

Neural Forge is built on open-source software:

- Based on [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
- Uses [ONNX Runtime](https://onnxruntime.ai/)
- Integrates [TensorFlow Lite](https://www.tensorflow.org/lite)

## 📄 License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

---

**Neural Forge** - Transforming mobile devices into AI powerhouses 🔥
