# React Native + Expo Compatibility Audit Report
## Google AI Edge Gallery Android App

**Audit Date**: April 14, 2026  
**App Version**: 1.0.11 (versionCode 23)  
**Source Repository**: google-ai-edge/gallery  
**Total Kotlin Files**: 170  
**Target Platform**: React Native + Expo

---

## Executive Summary

The Google AI Edge Gallery is a sophisticated on-device LLM application built entirely with Android/Kotlin technologies. This audit evaluates the feasibility of porting it to React Native + Expo. The app's architecture centers around **on-device inference using LiteRT-LM and AICore**, which presents the most significant compatibility challenge. While the UI layer (Jetpack Compose) can be reimplemented in React Native, the **inference runtime requires substantial native module development** to bridge the gap between JavaScript and native LLM engines.

**Key Findings**:
- ✅ **Feasible**: UI, navigation, state management, HuggingFace OAuth, skills system (WebView-based)
- ⚠️ **Complex**: Model downloads, file management, CameraX integration
- ❌ **Critical Blocker**: LiteRT-LM inference runtime (no RN wrapper exists)
- 🔧 **Requires Custom Native Modules**: LLM inference, advanced camera features, AICore integration

**Estimated Effort**: 6-9 months for full-featured port (3-4 months for MVP with basic chat)

---

## 1. Architecture Overview

### 1.1 What the App Does

AI Edge Gallery is an **on-device LLM sandbox** that enables users to:
1. Download and run open-source LLMs (Gemma 2, Gemma 3, Gemma 4, etc.) locally on their mobile device
2. Chat with models in multi-turn conversations (with optional "thinking mode")
3. Use multimodal capabilities (Ask Image via CameraX, Ask Audio transcription)
4. Benchmark model performance (tokens/sec, latency)
5. Extend LLMs with "Agent Skills" (text-only personas, JavaScript functions in WebView, native intents)
6. Execute specialized tasks: Mobile Actions (device control via function calling), Tiny Garden (mini-game)

### 1.2 Core Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                             │
│  - Navigation (Compose Navigation)                      │
│  - 170 Kotlin files: Composables, ViewModels            │
│  - Screens: Home, Chat, Benchmark, Settings, etc.      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  Business Logic (Kotlin)                                │
│  - ViewModels (Hilt-injected)                           │
│  - Repositories: DownloadRepository, DataStoreRepository│
│  - Data Models: Model, Task, Config                     │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  Runtime Layer (LLM Inference) ★ CRITICAL                │
│  - LlmModelHelper interface                             │
│  - LiteRT-LM implementation (primary)                   │
│  - AICore implementation (ML Kit GenAI)                 │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  Platform Services                                       │
│  - WorkManager (downloads), DataStore (settings)        │
│  - CameraX (image input), Firebase (analytics/FCM)     │
│  - WebView (skills execution), AppAuth (HuggingFace)   │
└─────────────────────────────────────────────────────────┘
```

### 1.3 Data Flow Example (Chat Inference)

1. User types message → `LlmChatViewModel.sendMessage()`
2. ViewModel calls `LlmModelHelper.runInference()`
3. LiteRT-LM runtime loads `.tflite` model file, executes inference
4. Tokens stream back via `ResultListener` callback
5. ViewModel updates `chatMessages` StateFlow
6. Compose UI re-renders with new message

---

## 2. Dependency Mapping: Android → React Native/Expo

### 2.1 Critical Dependencies (No Direct Equivalent)

| Android Dependency | Version | Purpose | RN/Expo Equivalent | Gap Assessment |
|-------------------|---------|---------|-------------------|----------------|
| **litertlm-android** | 0.10.0 | On-device LLM inference runtime | ❌ **None** | **CRITICAL BLOCKER**: Core inference engine. Would need custom Expo module wrapping LiteRT C++ SDK or alternative (ExecuTorch). Estimated 2-3 months development. |
| **mlkit-genai-prompt** | 1.0.0-beta2 | AICore integration (Android 12+ system-level LLM) | ❌ None | Android-exclusive. Skip for RN port (AICore unavailable on iOS). |
| **play-services-tflite-java** | 16.4.0 | TensorFlow Lite GPU delegate | ⚠️ `@react-native-community/tensorflow` (outdated) | Expo doesn't officially support TFLite. Would need custom module. |
| **Hilt** | 2.57.2 | Dependency injection | ✅ Context API, Zustand, or React Query | Pure JS DI pattern, no native bridge needed. |

### 2.2 UI & Navigation

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| Jetpack Compose (BOM) | 2026.02.00 | React Native components | ✅ **Full rewrite needed**, but straightforward. Use `react-navigation` v6+. |
| Compose Navigation | 2.8.9 | `@react-navigation/native` | ✅ Equivalent functionality. |
| Material3 | - | `@react-native-paper` or `tamagui` | ✅ Similar design system support. |
| Material Icons Extended | 1.7.8 | `@expo/vector-icons` | ✅ Icon coverage adequate. |

### 2.3 State Management & Persistence

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| DataStore (Proto) | 1.1.7 | `expo-secure-store` + AsyncStorage | ✅ Async storage + encryption covered. |
| Kotlin Flows / StateFlow | - | Zustand, MobX, or React Query | ✅ Similar reactive state management. |
| WorkManager | 2.10.0 | `expo-task-manager` + `expo-background-fetch` | ⚠️ Background downloads more limited on iOS. |
| SharedPreferences | - | `@react-native-async-storage/async-storage` | ✅ Direct equivalent. |

### 2.4 Network & Downloads

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| OkHttp (via WorkManager) | - | `expo-file-system` + `fetch` | ⚠️ Resumable downloads require `expo-file-system.downloadAsync()` with session support. |
| Gson | 2.12.1 | `JSON.parse()` / `JSON.stringify()` | ✅ Native JS. |
| Protobuf JavaLite | 4.26.1 | `protobuf.js` or `@bufbuild/protobuf` | ✅ JS protobuf libraries exist. |

### 2.5 Authentication & OAuth

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| AppAuth (OpenID) | 0.11.1 | `expo-auth-session` | ✅ **Direct equivalent**. OAuth2/OIDC flows supported, including HuggingFace. |
| Security Crypto | 1.1.0 | `expo-secure-store` | ✅ Encrypted storage on device. |

### 2.6 Camera & Media

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| CameraX (core, camera2, lifecycle, view) | 1.4.2 | `expo-camera` | ⚠️ `expo-camera` provides basic functionality. Advanced CameraX features (e.g., MLKit integration) would require custom module. |
| ExifInterface | 1.4.1 | `expo-image-manipulator` + `expo-media-library` | ✅ EXIF reading supported. |

### 2.7 Firebase & Analytics

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| Firebase BOM | 33.16.0 | `expo-firebase-analytics` + `expo-firebase-messaging` | ✅ Expo Firebase plugins cover analytics & push notifications. |
| Firebase Analytics | (via BOM) | `@react-native-firebase/analytics` | ✅ Full feature parity. |
| Firebase Messaging (FCM) | (via BOM) | `@react-native-firebase/messaging` | ✅ Push notifications work identically. |

### 2.8 WebView & Skills System

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| WebKit | 1.14.0 | `react-native-webview` | ✅ **Excellent news**: Skills system (HTML/JS in hidden WebView) maps directly to `react-native-webview`. The `ai_edge_gallery_get_result` bridge can be reimplemented with `injectedJavaScript` + `onMessage`. |
| WebView JS Bridge | Custom | `webView.postMessage()` | ✅ Equivalent messaging APIs. |

### 2.9 Miscellaneous

| Android | Version | RN/Expo Equivalent | Compatibility |
|---------|---------|-------------------|---------------|
| Markdown Rendering (Commonmark, Richtext) | 1.0.0-alpha02 | `react-native-markdown-display` | ✅ Multiple markdown libraries available. |
| Splash Screen | 1.2.0-beta01 | `expo-splash-screen` | ✅ Built into Expo. |
| Process Lifecycle | 2.8.7 | `AppState` API | ✅ React Native built-in. |
| Coroutines | - | `async/await` | ✅ JavaScript native concurrency. |

---

## 3. Compatibility Assessment by Layer

### 3.1 UI Layer (Jetpack Compose → React Native)

**Status**: ✅ **Fully Portable** (requires rewrite)

**Scope**:
- 170 Kotlin files of Compose UI components
- Screens: Home (task tiles), Chat (message list), Benchmark (progress indicators), Model Manager (download cards), Settings

**Effort**:
- **UI Rewrite**: ~6-8 weeks for all screens
- **Component Library**: Use `react-native-paper` or custom components
- **Navigation**: `react-navigation` stack + bottom tabs
- **Theming**: Material Design 3 colors → `react-native-paper` theme

**Example Translation**:
```kotlin
// Android (Compose)
@Composable
fun ChatMessage(message: Message) {
  Column(modifier = Modifier.padding(8.dp)) {
    Text(text = message.sender, style = MaterialTheme.typography.labelSmall)
    Text(text = message.content)
  }
}
```

```javascript
// React Native
const ChatMessage = ({ message }) => (
  <View style={{ padding: 8 }}>
    <Text style={styles.label}>{message.sender}</Text>
    <Text>{message.content}</Text>
  </View>
);
```

### 3.2 State Management & Business Logic

**Status**: ✅ **Portable** (architecture refactor)

**Scope**:
- ViewModels → React hooks + Zustand/MobX
- Kotlin Flows → Observables/Reactive state
- Hilt DI → Context API or function composition

**Effort**: ~3-4 weeks

**Example Translation**:
```kotlin
// Android (ViewModel + Flow)
class LlmChatViewModel @Inject constructor(
  private val modelHelper: LlmModelHelper
) : ViewModel() {
  private val _messages = MutableStateFlow<List<Message>>(emptyList())
  val messages: StateFlow<List<Message>> = _messages.asStateFlow()
  
  fun sendMessage(text: String) {
    modelHelper.runInference(...)
  }
}
```

```javascript
// React Native (Zustand + hooks)
const useChatStore = create((set) => ({
  messages: [],
  sendMessage: async (text) => {
    const response = await runInference(text);
    set((state) => ({ messages: [...state.messages, response] }));
  },
}));
```

### 3.3 LLM Inference Runtime (LiteRT-LM)

**Status**: ❌ **CRITICAL BLOCKER**

**The Problem**:
LiteRT-LM is Google's Android-specific runtime for on-device LLM inference. It wraps TensorFlow Lite with optimizations for large language models (KV cache management, token streaming, etc.). There is **no official React Native binding**.

**Root Cause Analysis**:
```kotlin
// Android: LiteRT-LM API (runtime/LlmModelHelper.kt)
fun runInference(
  model: Model,
  input: String,
  resultListener: (partialResult: String, done: Boolean) -> Unit,
  ...
) {
  // Loads .tflite model file
  // Executes tokenization → inference → decoding
  // Streams tokens via callback
}
```

This API:
1. Loads multi-GB `.tflite` model files from disk
2. Manages GPU/NPU acceleration (TFLite delegates)
3. Streams tokens asynchronously during generation
4. Handles conversation history (KV cache)

**React Native Gaps**:
- No `litertlm-android` npm package
- No community-maintained RN bindings for TFLite with LLM support
- Existing `@tensorflow/tfjs-react-native` is outdated and doesn't support LLM features

**Solutions (Ranked by Effort)**:

#### Option A: Custom Expo Module Wrapping LiteRT
**Effort**: 8-12 weeks  
**Approach**:
1. Create Expo module: `expo-litert-lm`
2. Use LiteRT C++ SDK (or wrap Android JAR via JNI)
3. Bridge inference API to JavaScript:
   ```typescript
   // expo-litert-lm (TypeScript)
   export async function* runInference(
     modelPath: string,
     prompt: string
   ): AsyncGenerator<string> {
     // Native module streams tokens back
   }
   ```
4. Handle file I/O, threading, callbacks

**Pros**: Full LiteRT feature parity  
**Cons**: Significant C++/native expertise required, maintenance burden

#### Option B: React Native ExecuTorch
**Effort**: 6-8 weeks  
**Approach**:
Use Meta's ExecuTorch (PyTorch on-device runtime) instead of LiteRT:
1. `react-native-executorch` (experimental community package)
2. Convert models from `.tflite` to ExecuTorch format
3. Simpler API, lighter runtime

**Pros**: Growing RN community support, PyTorch ecosystem  
**Cons**: Model conversion overhead, less Google AI Edge integration

#### Option C: ONNX Runtime Mobile
**Effort**: 4-6 weeks  
**Approach**:
1. Use `onnxruntime-react-native`
2. Convert `.tflite` models to ONNX format
3. Implement streaming inference wrapper

**Pros**: Mature cross-platform runtime, existing RN bindings  
**Cons**: Model conversion required, may lose optimizations

#### Option D: WebAssembly (Browser-Based)
**Effort**: 3-4 weeks  
**Approach**:
1. Run inference in WebView via ONNX.js or TensorFlow.js
2. Bridge results to React Native via `postMessage`

**Pros**: Zero native code, rapid prototyping  
**Cons**: Slower performance, no GPU access, memory limits

**Recommendation**: **Option A (Custom Expo Module)** for production app, **Option D (WASM)** for rapid MVP/proof-of-concept.

### 3.4 AICore Integration (ML Kit GenAI)

**Status**: ❌ **Not Portable** (Android 12+ exclusive)

AICore is Android's system-level LLM service (Pixel devices only). Skip for React Native port—this feature cannot exist on iOS. Focus on LiteRT-LM equivalent for cross-platform support.

### 3.5 Model Download & File Management

**Status**: ⚠️ **Complex but Doable**

**Android Implementation**:
- WorkManager for resumable background downloads
- Files stored in `getExternalFilesDir()` (app-specific storage)
- Unzip `.tflite` files post-download
- Progress tracking via LiveData

**React Native Solution**:
```javascript
import * as FileSystem from 'expo-file-system';

// Resumable download with progress
const download = FileSystem.createDownloadResumable(
  modelUrl,
  FileSystem.documentDirectory + 'models/' + fileName,
  {},
  (progress) => {
    const percent = progress.totalBytesWritten / progress.totalBytesExpectedToWrite;
    setDownloadProgress(percent);
  }
);

const result = await download.downloadAsync();
```

**Challenges**:
1. iOS background download restrictions (use `expo-task-manager`)
2. Large file handling (4GB+ models) → test memory limits
3. Unzipping: Use `expo-file-system.unzipAsync()` or `react-native-zip-archive`

**Effort**: ~2-3 weeks

### 3.6 HuggingFace OAuth & Gated Model Access

**Status**: ✅ **Fully Portable**

**Android**:
- Uses AppAuth library (OpenID Connect)
- Redirects to HuggingFace OAuth flow
- Stores access token securely (EncryptedSharedPreferences)

**React Native**:
```javascript
import * as AuthSession from 'expo-auth-session';
import * as SecureStore from 'expo-secure-store';

const [request, response, promptAsync] = AuthSession.useAuthRequest(
  {
    clientId: 'YOUR_HF_CLIENT_ID',
    scopes: ['read-repos'],
    redirectUri: AuthSession.makeRedirectUri({ useProxy: true }),
  },
  { authorizationEndpoint: 'https://huggingface.co/oauth/authorize' }
);

// Store token
await SecureStore.setItemAsync('hf_token', response.params.access_token);
```

**Effort**: ~1 week

### 3.7 Agent Skills System (WebView-Based)

**Status**: ✅ **Excellent Compatibility** 🎉

**How It Works (Android)**:
1. Skills are directories with `SKILL.md` (metadata) + `scripts/index.html` (JS logic)
2. App loads `index.html` into hidden WebView
3. Calls `window.ai_edge_gallery_get_result(data)` from Kotlin
4. JavaScript returns JSON result

**React Native Translation**:
```javascript
import { WebView } from 'react-native-webview';

const executeSkill = (skillPath, data) => {
  return new Promise((resolve) => {
    webViewRef.current.injectJavaScript(`
      (async () => {
        const result = await window.ai_edge_gallery_get_result('${JSON.stringify(data)}');
        window.ReactNativeWebView.postMessage(result);
      })();
    `);
    
    const handleMessage = (event) => {
      const result = JSON.parse(event.nativeEvent.data);
      resolve(result);
    };
  });
};
```

**Key Points**:
- All built-in skills (Wikipedia, QR code, interactive map, mood tracker) will work identically
- Native intent skills (send email, send SMS) need RN equivalents:
  - Email: `react-native-email-link`
  - SMS: `react-native-sms`

**Effort**: ~2 weeks

### 3.8 Camera Integration (Ask Image)

**Status**: ⚠️ **Basic Support, Advanced Features Limited**

**Android CameraX Features**:
- Camera preview + capture
- Image analysis pipeline (for future MLKit integration)
- EXIF data preservation

**React Native**:
```javascript
import { Camera } from 'expo-camera';

const { status } = await Camera.requestCameraPermissionsAsync();
const photo = await cameraRef.current.takePictureAsync();
// Pass photo.uri to LLM inference
```

**Limitations**:
- CameraX's advanced features (HDR, night mode) not available in `expo-camera`
- If future Android app adds on-device image preprocessing (e.g., MLKit Vision), would need custom module

**Effort**: ~1 week for basic capture, +2 weeks if advanced features needed

---

## 4. Hardest Compatibility Blockers

### 4.1 LiteRT-LM Inference Runtime
**Impact**: 🔴 **Critical**  
**Details**: See Section 3.3. This is the app's core functionality. Without a solution, the app cannot run LLMs on-device.  
**Recommended Path**: Custom Expo module wrapping LiteRT C++ SDK (8-12 weeks).

### 4.2 GPU Acceleration & TFLite Delegates
**Impact**: 🟠 **High**  
**Details**: LiteRT-LM uses TFLite GPU delegate for fast inference. React Native lacks official TFLite GPU support. ONNX Runtime and ExecuTorch have GPU support but require platform-specific setup.  
**Mitigation**: Test performance with CPU-only inference initially. Add GPU support in Phase 2.

### 4.3 Background Model Downloads (iOS Restrictions)
**Impact**: 🟡 **Medium**  
**Details**: iOS limits background downloads more strictly than Android (WorkManager). Large model files (4GB+) may time out.  
**Mitigation**: Use `expo-task-manager` + user notifications. Recommend downloads over Wi-Fi only.

### 4.4 AICore (Android 12+ System LLM)
**Impact**: 🟢 **Low** (feature exclusion acceptable)  
**Details**: AICore is Pixel-exclusive. Skip this runtime for RN version—focus on cross-platform LiteRT solution.

---

## 5. What Can Be Ported with Zero Native Code

**Pure JavaScript/TypeScript** (no native modules required):

1. **UI Layouts**: All screens, navigation, theming (React Native components)
2. **State Management**: Message history, model configs, user preferences (Zustand/Redux)
3. **Model Metadata Parsing**: `model_allowlist.json`, skill `SKILL.md` files (pure JSON/Markdown)
4. **HuggingFace OAuth**: `expo-auth-session` (built-in)
5. **Skills System Logic**: WebView bridge, skill discovery, text-only personas
6. **Markdown Rendering**: Chat messages, model info cards
7. **Analytics Events**: Firebase Analytics (Expo plugin)
8. **Push Notifications**: FCM (Expo plugin)

**Total Scope**: ~30-40% of app functionality (UI + business logic).

---

## 6. Native Module Requirements

**Must-Write Custom Expo Modules**:

1. **`expo-litert-lm`** (or equivalent):
   - LLM inference runtime bridge
   - Model loading, tokenization, generation
   - Token streaming callbacks
   - ~8-12 weeks development

2. **`expo-model-file-utils`** (optional):
   - Large file unzipping (if `expo-file-system` insufficient)
   - Model validation (SHA256 checks)
   - ~1 week

**Can Use Existing Libraries**:
- Camera: `expo-camera`
- File System: `expo-file-system`
- Audio: `expo-av`
- Secure Storage: `expo-secure-store`

---

## 7. Estimated Effort Breakdown by Layer

| Layer | Complexity | Effort (weeks) | Can Start Early? |
|-------|-----------|----------------|------------------|
| UI (all screens) | Medium | 6-8 | ✅ Yes |
| Navigation | Low | 1 | ✅ Yes |
| State management | Medium | 3-4 | ✅ Yes |
| Model download | High | 2-3 | ✅ Yes |
| HuggingFace OAuth | Low | 1 | ✅ Yes |
| Skills system (WebView) | Medium | 2 | ✅ Yes |
| Camera integration | Low | 1 | ✅ Yes |
| **LLM Inference (LiteRT)** | **Critical** | **8-12** | ❌ Blocks MVP |
| Benchmark UI | Medium | 1-2 | ⚠️ After inference |
| Firebase setup | Low | 1 | ✅ Yes |
| **Total (Full Port)** | - | **26-36 weeks** | - |
| **MVP (Chat Only)** | - | **12-16 weeks** | - |

**MVP Scope** (3-4 months):
- Basic chat UI (text-only, no thinking mode)
- One model (e.g., Gemma 2B)
- Manual model file loading (skip download UI)
- Inference via custom Expo module
- No skills, no camera, no benchmark

**Full Port** (6-9 months):
- All tasks (Chat, Ask Image, Agent Skills, Benchmark, Mobile Actions, Tiny Garden)
- Model manager with HuggingFace OAuth + downloads
- Skills system with all built-in skills
- Camera integration
- Performance benchmarking
- Push notifications for download completion

---

## 8. Risk Assessment

### High Risks
1. **LiteRT-LM Native Module Performance**: If custom module doesn't match native performance (tokens/sec), user experience degrades. Mitigation: Prototype early, benchmark against Android.
2. **iOS App Store Rejection**: Large model file downloads (4GB+) may trigger App Store review concerns. Mitigation: Clearly document on-device privacy in metadata.
3. **Memory Limits**: React Native bridge overhead + large models may cause OOM crashes on low-end devices. Mitigation: Test on 6GB RAM devices minimum.

### Medium Risks
1. **Skills System Compatibility**: Some skills may use Android-specific WebView APIs. Mitigation: Test all built-in skills early, document breaking changes.
2. **Model Format Lock-In**: If switching from TFLite to ONNX/ExecuTorch, must convert all models. Mitigation: Maintain conversion scripts in repo.

### Low Risks
1. **UI Parity**: Compose → React Native translation may introduce subtle layout differences. Mitigation: Screenshot testing.
2. **Firebase FCM**: Push notification payloads work identically on RN. Mitigation: Test deeplinks early.

---

## 9. Recommendations

### Phase 0: Proof of Concept (4 weeks)
1. Create Expo app with basic chat UI
2. Implement WebAssembly-based inference (TensorFlow.js in WebView) with small model (Gemma 0.5B)
3. Validate token streaming and UI responsiveness
4. **Goal**: Confirm RN architecture viability

### Phase 1: Native Inference Module (8-12 weeks)
1. Develop `expo-litert-lm` module (or select ExecuTorch/ONNX alternative)
2. Port one model (Gemma 2B) to new runtime
3. Benchmark performance vs Android native
4. **Goal**: Achieve 80%+ token/sec parity

### Phase 2: MVP Features (8 weeks)
1. Port core chat UI, message history, model loading
2. Implement model download (HuggingFace OAuth + file management)
3. Add basic settings and model manager
4. **Goal**: Shippable MVP for beta testing

### Phase 3: Advanced Features (8-12 weeks)
1. Port Agent Skills system (WebView bridge)
2. Add Ask Image (CameraX → expo-camera)
3. Benchmark UI
4. Mobile Actions / Tiny Garden tasks
5. **Goal**: Feature parity with Android

### Testing Strategy
- **Unit Tests**: Zustand stores, utility functions (Jest)
- **Integration Tests**: Inference pipeline, download flow (Detox)
- **Performance Tests**: Token/sec, memory usage, app launch time
- **Device Coverage**: iOS 17+ (iPhone 12+), Android 12+ (8GB RAM minimum)

---

## 10. Conclusion

Porting Google AI Edge Gallery to React Native + Expo is **feasible but requires significant native development work**, primarily around the LLM inference runtime. The app's architecture is well-suited for React Native in principle—state management, UI, and file operations map cleanly—but the reliance on Android-specific libraries (LiteRT-LM, CameraX, Hilt) creates substantial friction.

**Key Success Factors**:
1. Secure native expertise (Objective-C/Swift + Kotlin) for Expo module development
2. Decide on inference runtime early (LiteRT wrapper vs ExecuTorch vs ONNX)
3. Start with MVP (chat-only) to validate architecture before full port
4. Plan for 6-9 months total timeline for production-ready app

**Verdict**: Recommended for teams with native mobile + RN expertise and realistic timeline expectations. Not suitable for rapid port (< 3 months) or teams without C++/native experience.
