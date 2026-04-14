# React Native + Expo Migration Plan
## Google AI Edge Gallery Android → Cross-Platform

**Plan Version**: 1.0  
**Target**: React Native 0.76+ with Expo SDK 52+  
**Timeline**: 6-9 months (full port), 3-4 months (MVP)  
**Last Updated**: April 14, 2026

---

## Table of Contents

1. [Migration Strategy Overview](#1-migration-strategy-overview)
2. [Recommended Approach: Expo Managed + Custom Modules](#2-recommended-approach-expo-managed--custom-modules)
3. [Project Structure & Monorepo Setup](#3-project-structure--monorepo-setup)
4. [Phase-by-Phase Migration Plan](#4-phase-by-phase-migration-plan)
5. [Dependency Mapping & Package Selection](#5-dependency-mapping--package-selection)
6. [LiteRT-LM Native Inference Strategy](#6-litert-lm-native-inference-strategy)
7. [HuggingFace OAuth Implementation](#7-huggingface-oauth-implementation)
8. [Skills System Migration](#8-skills-system-migration)
9. [Model Download & File Management](#9-model-download--file-management)
10. [Camera Integration (Ask Image)](#10-camera-integration-ask-image)
11. [State Management Architecture](#11-state-management-architecture)
12. [UI Migration Guide](#12-ui-migration-guide)
13. [Testing Strategy](#13-testing-strategy)
14. [Performance Benchmarks & Targets](#14-performance-benchmarks--targets)
15. [MVP Scope Definition](#15-mvp-scope-definition)

---

## 1. Migration Strategy Overview

### 1.1 Key Principles

1. **Incremental Port, Not Rewrite**: Migrate by feature layer (UI → State → Inference), not file-by-file.
2. **Native Module First**: Block all work on inference-dependent features until LLM runtime is stable.
3. **Expo-First Approach**: Use Expo SDK 52+ for maximum productivity; only drop to bare workflow if custom modules require it.
4. **Cross-Platform Parity**: Maintain iOS + Android support; skip Android-exclusive features (AICore).
5. **Performance Validation**: Match or exceed 80% of Android's token/sec throughput.

### 1.2 Migration Phases (6-9 Months)

| Phase | Duration | Goal | Blockers | Deliverable |
|-------|----------|------|----------|-------------|
| **0. PoC** | 4 weeks | Validate RN architecture | None | Chat UI + WASM inference |
| **1. Native Module** | 8-12 weeks | LLM inference in RN | Native expertise | `expo-litert-lm` package |
| **2. MVP** | 8 weeks | Shippable chat app | Phase 1 | TestFlight/Play Store beta |
| **3. Feature Parity** | 8-12 weeks | All tasks + skills | Phase 2 | v1.0 release |

### 1.3 Team Requirements

- **1 Senior React Native Engineer**: State management, navigation, UI components
- **1 Native Engineer** (iOS + Android): Expo module development, TFLite/LiteRT integration
- **1 Backend/Infra Engineer** (part-time): HuggingFace OAuth, model hosting, Firebase setup
- **1 QA/Test Engineer** (part-time): Detox tests, device farm testing

---

## 2. Recommended Approach: Expo Managed + Custom Modules

### 2.1 Why Expo Managed Workflow?

**Pros**:
- ✅ Rapid iteration: EAS Build, OTA updates, dev client
- ✅ 90% of features covered by Expo SDK (auth, file system, camera, notifications)
- ✅ Simplified CI/CD with `eas build` and `eas submit`
- ✅ Better debugging with Expo Go + dev client

**Cons**:
- ⚠️ Custom native modules require `expo-module-scripts` config (manageable)
- ⚠️ AICore-like Android-exclusive features harder to integrate (not needed)

**Decision**: Use **Expo Managed Workflow** with custom local Expo module for LLM inference.

### 2.2 Why NOT Bare React Native?

Bare workflow offers more control but adds complexity:
- Manual linking of native libraries (Podfile, Gradle)
- Harder to maintain cross-platform consistency
- Loses Expo's dev tooling (EAS, Expo Go)

**Exception**: If LiteRT integration proves impossible with Expo module system, fall back to bare workflow in Phase 1.

### 2.3 Alternative: Monorepo with Expo + React Native CLI

**Use Case**: If you need both Expo app (consumer) and bare RN app (enterprise) from same codebase.

**Structure**:
```
gallery-monorepo/
├── apps/
│   ├── expo-app/       # Expo managed
│   └── bare-app/       # React Native CLI (optional)
├── packages/
│   ├── shared-ui/      # React Native components
│   ├── inference/      # LLM runtime wrapper
│   └── expo-litert/    # Custom Expo module
└── package.json        # Yarn workspaces
```

**Recommendation**: Start with single Expo app; add monorepo later if needed.

---

## 3. Project Structure & Monorepo Setup

### 3.1 Recommended Directory Structure

```
gallery-rn/
├── app/                         # Expo app (managed workflow)
│   ├── app.json                 # Expo config
│   ├── package.json
│   ├── src/
│   │   ├── screens/             # UI screens (Chat, Home, Benchmark)
│   │   ├── components/          # Reusable UI components
│   │   ├── store/               # Zustand stores (models, chat, settings)
│   │   ├── services/            # API clients (HuggingFace, Firebase)
│   │   ├── inference/           # LLM inference abstraction layer
│   │   ├── skills/              # Skills system logic
│   │   ├── navigation/          # React Navigation setup
│   │   └── utils/               # Helpers (file I/O, markdown parser)
│   ├── assets/                  # Images, fonts
│   └── modules/                 # Local Expo modules (if using CNG)
│       └── expo-litert-lm/      # Custom inference module
├── scripts/                     # Build scripts, model conversion
├── docs/                        # Migration docs
└── package.json                 # Yarn/npm workspaces (if monorepo)
```

### 3.2 Configuration Files

**`app.json` (Expo Config)**:
```json
{
  "expo": {
    "name": "AI Edge Gallery",
    "slug": "gallery-rn",
    "version": "1.0.0",
    "platforms": ["ios", "android"],
    "plugins": [
      "expo-camera",
      "expo-file-system",
      "@react-native-firebase/app",
      "./modules/expo-litert-lm/plugin"
    ],
    "ios": {
      "bundleIdentifier": "com.google.aiedge.gallery",
      "supportsTablet": true,
      "infoPlist": {
        "NSCameraUsageDescription": "Used to capture images for Ask Image feature"
      }
    },
    "android": {
      "package": "com.google.aiedge.gallery",
      "permissions": [
        "CAMERA",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE"
      ]
    }
  }
}
```

**`package.json` Dependencies** (MVP):
```json
{
  "dependencies": {
    "expo": "~52.0.0",
    "react-native": "0.76.5",
    "@react-navigation/native": "^6.1.18",
    "@react-navigation/stack": "^6.4.1",
    "@react-navigation/bottom-tabs": "^6.6.1",
    "zustand": "^5.0.2",
    "expo-camera": "~16.0.0",
    "expo-file-system": "~18.0.0",
    "expo-auth-session": "~6.0.0",
    "expo-secure-store": "~14.0.0",
    "@react-native-firebase/app": "^21.6.0",
    "@react-native-firebase/analytics": "^21.6.0",
    "react-native-webview": "^13.12.4",
    "react-native-markdown-display": "^7.0.2",
    "react-native-paper": "^5.12.5"
  }
}
```

---

## 4. Phase-by-Phase Migration Plan

### **Phase 0: Proof of Concept (Weeks 1-4)**

**Goal**: Validate that React Native can run LLM inference with acceptable performance.

**Tasks**:
1. **Week 1**: Set up Expo app, navigation, basic chat UI
   - Create project: `npx create-expo-app gallery-rn --template blank-typescript`
   - Install React Navigation, Zustand
   - Build chat screen with message list (mock data)

2. **Week 2**: Implement WebAssembly inference (PoC only)
   - Embed TensorFlow.js in WebView
   - Load small model (Gemma 0.5B quantized)
   - Stream tokens back to React Native via `postMessage`

3. **Week 3**: Test inference pipeline
   - Benchmark: Measure tokens/sec on iPhone 15 Pro, Pixel 8
   - Validate memory usage (should stay under 2GB)
   - Test multi-turn conversations

4. **Week 4**: Architecture review
   - Document findings (performance, bottlenecks)
   - Decide: Continue with WebAssembly or prioritize native module?
   - Present to stakeholders

**Deliverable**: Working prototype with chat UI + inference. Not production-ready.

---

### **Phase 1: Native Inference Module (Weeks 5-16)**

**Goal**: Build production-grade LLM inference for React Native.

**Option A: Custom Expo Module Wrapping LiteRT** (Recommended)

**Week 5-6: Module Scaffolding**
```bash
cd app/modules
npx create-expo-module expo-litert-lm
```

**File Structure**:
```
modules/expo-litert-lm/
├── android/
│   └── src/main/java/expo/modules/litertlm/
│       ├── ExpoLiteRTModule.kt        # Main module
│       ├── InferenceEngine.kt         # TFLite inference
│       └── TokenStreamCallback.kt     # Event emitter
├── ios/
│   └── ExpoLiteRTModule.swift         # iOS implementation
├── src/
│   └── index.ts                       # TypeScript API
└── expo-module.config.json
```

**Week 7-10: Android Implementation**

1. **Dependency Setup** (`android/build.gradle`):
   ```gradle
   dependencies {
     implementation "com.google.ai.edge.litertlm:litertlm-android:0.10.0"
     implementation "com.google.android.gms:play-services-tflite-java:16.4.0"
     implementation "com.google.android.gms:play-services-tflite-gpu:16.4.0"
   }
   ```

2. **Inference Engine** (`InferenceEngine.kt`):
   ```kotlin
   class InferenceEngine(private val context: Context) {
     private var llmInference: LlmInference? = null
     
     fun loadModel(modelPath: String, options: InferenceOptions): Result<Unit> {
       try {
         llmInference = LlmInference.createInstance(
           context = context,
           modelPath = modelPath,
           maxTokens = options.maxTokens,
           temperature = options.temperature,
           topK = options.topK
         )
         return Result.success(Unit)
       } catch (e: Exception) {
         return Result.failure(e)
       }
     }
     
     suspend fun generateStream(
       prompt: String,
       onToken: (String) -> Unit
     ): Result<String> {
       val llm = llmInference ?: return Result.failure(Error("Model not loaded"))
       
       return llm.generateResponseAsync(prompt) { partialResponse ->
         onToken(partialResponse)
       }
     }
   }
   ```

3. **Expo Module Bridge** (`ExpoLiteRTModule.kt`):
   ```kotlin
   @ExpoModule("ExpoLiteRT")
   class ExpoLiteRTModule(context: ModuleRegistry) : Module() {
     private val inferenceEngine = InferenceEngine(context)
     
     @ExpoMethod
     fun loadModel(modelPath: String, options: Map<String, Any>, promise: Promise) {
       inferenceEngine.loadModel(modelPath, options.toInferenceOptions())
         .onSuccess { promise.resolve(null) }
         .onFailure { promise.reject("LOAD_ERROR", it.message, it) }
     }
     
     @ExpoMethod
     fun generateStream(prompt: String, promise: Promise) {
       GlobalScope.launch {
         inferenceEngine.generateStream(prompt) { token ->
           sendEvent("onToken", mapOf("token" to token))
         }
       }
     }
   }
   ```

**Week 11-14: iOS Implementation**

1. **Dependency Setup** (`ExpoLiteRTModule.podspec`):
   ```ruby
   Pod::Spec.new do |s|
     s.dependency 'TensorFlowLiteSwift', '~> 2.15.0'
     s.dependency 'TensorFlowLiteGpuDelegate', '~> 2.15.0'
   end
   ```

2. **Swift Bridge** (`ExpoLiteRTModule.swift`):
   ```swift
   import ExpoModulesCore
   import TensorFlowLite
   
   public class ExpoLiteRTModule: Module {
     private var interpreter: Interpreter?
     
     public func definition() -> ModuleDefinition {
       Name("ExpoLiteRT")
       
       AsyncFunction("loadModel") { (modelPath: String, options: [String: Any]) in
         let interpreterOptions = Interpreter.Options()
         interpreterOptions.threadCount = 4
         interpreter = try Interpreter(modelPath: modelPath, options: interpreterOptions)
         try interpreter?.allocateTensors()
       }
       
       AsyncFunction("generateStream") { (prompt: String) in
         // Tokenize, invoke, decode (simplified)
         let inputTokens = tokenize(prompt)
         try interpreter?.invoke()
         let outputTokens = interpreter?.output(at: 0)
         let text = decode(outputTokens)
         sendEvent("onToken", ["token": text])
       }
       
       Events("onToken")
     }
   }
   ```

**Week 15-16: Testing & Optimization**
- Unit tests for module (Jest + native tests)
- Benchmark: Compare token/sec vs Android native
- Memory profiling (Instruments on iOS, Android Profiler)
- Fix GPU delegate issues

**Deliverable**: `expo-litert-lm` npm package (or local module) that loads models and streams tokens.

---

**Option B: React Native ExecuTorch** (Alternative)

**Week 5-12**: Use `react-native-executorch` (experimental)

**Installation**:
```bash
npm install react-native-executorch
cd ios && pod install
```

**Model Conversion**:
```python
# Convert .tflite to ExecuTorch format
import torch
from executorch.exir import to_edge

# Load model, export to .pte
model.eval()
exported = torch.export.export(model, (example_input,))
edge_program = to_edge(exported)
edge_program.save("gemma2b.pte")
```

**Usage**:
```typescript
import { Module } from 'react-native-executorch';

const model = await Module.load('gemma2b.pte');
const tokens = await model.forward(inputTensor);
```

**Pros**: Simpler than LiteRT wrapper, PyTorch ecosystem  
**Cons**: Model conversion required, less mature

---

### **Phase 2: MVP Features (Weeks 17-24)**

**Goal**: Shippable app with core chat functionality.

**Week 17-18: State Management**

1. **Zustand Stores** (`src/store/`):

**`chatStore.ts`**:
```typescript
import { create } from 'zustand';
import { generateStream } from '@/modules/expo-litert-lm';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

interface ChatStore {
  messages: Message[];
  isGenerating: boolean;
  sendMessage: (text: string) => Promise<void>;
  stopGeneration: () => void;
}

export const useChatStore = create<ChatStore>((set, get) => ({
  messages: [],
  isGenerating: false,
  
  sendMessage: async (text: string) => {
    const userMessage: Message = {
      id: uuid(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    };
    
    set({ messages: [...get().messages, userMessage], isGenerating: true });
    
    let assistantText = '';
    const assistantMessage: Message = {
      id: uuid(),
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    };
    
    // Listen for token stream
    const subscription = ExpoLiteRT.addListener('onToken', (event) => {
      assistantText += event.token;
      set((state) => ({
        messages: state.messages.map((m) =>
          m.id === assistantMessage.id ? { ...m, content: assistantText } : m
        ),
      }));
    });
    
    try {
      await generateStream(text);
      subscription.remove();
      set({ isGenerating: false });
    } catch (error) {
      console.error('Inference error:', error);
      subscription.remove();
      set({ isGenerating: false });
    }
  },
  
  stopGeneration: () => {
    ExpoLiteRT.stopGeneration();
    set({ isGenerating: false });
  },
}));
```

**`modelStore.ts`**:
```typescript
interface Model {
  name: string;
  displayName: string;
  sizeInBytes: number;
  localPath?: string;
  isDownloaded: boolean;
}

interface ModelStore {
  models: Model[];
  activeModel: Model | null;
  loadModel: (model: Model) => Promise<void>;
}

export const useModelStore = create<ModelStore>((set) => ({
  models: [],
  activeModel: null,
  
  loadModel: async (model: Model) => {
    if (!model.localPath) throw new Error('Model not downloaded');
    
    await ExpoLiteRT.loadModel(model.localPath, {
      maxTokens: 2048,
      temperature: 0.7,
      topK: 40,
    });
    
    set({ activeModel: model });
  },
}));
```

**Week 19-20: Model Download**

**`src/services/modelDownloader.ts`**:
```typescript
import * as FileSystem from 'expo-file-system';
import * as AuthSession from 'expo-auth-session';

export class ModelDownloader {
  private modelDir = FileSystem.documentDirectory + 'models/';
  
  async downloadModel(
    url: string,
    fileName: string,
    accessToken?: string,
    onProgress?: (percent: number) => void
  ): Promise<string> {
    // Create download resumable
    const downloadResumable = FileSystem.createDownloadResumable(
      url,
      this.modelDir + fileName,
      {
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
      },
      (downloadProgress) => {
        const percent =
          downloadProgress.totalBytesWritten / downloadProgress.totalBytesExpectedToWrite;
        onProgress?.(percent);
      }
    );
    
    const result = await downloadResumable.downloadAsync();
    if (!result?.uri) throw new Error('Download failed');
    
    return result.uri;
  }
  
  async unzipModel(zipPath: string): Promise<string> {
    const unzipDir = this.modelDir + 'extracted/';
    await FileSystem.unzipAsync(zipPath, unzipDir);
    return unzipDir;
  }
}
```

**Week 21-22: HuggingFace OAuth**

**`src/services/huggingface.ts`**:
```typescript
import * as AuthSession from 'expo-auth-session';
import * as SecureStore from 'expo-secure-store';

export class HuggingFaceAuth {
  private clientId = 'YOUR_CLIENT_ID';
  private redirectUri = AuthSession.makeRedirectUri({ useProxy: true });
  
  async login(): Promise<string> {
    const authRequest = new AuthSession.AuthRequest({
      clientId: this.clientId,
      scopes: ['read-repos'],
      redirectUri: this.redirectUri,
    });
    
    const result = await authRequest.promptAsync({
      authorizationEndpoint: 'https://huggingface.co/oauth/authorize',
    });
    
    if (result.type === 'success') {
      const { code } = result.params;
      const tokenResponse = await this.exchangeCodeForToken(code);
      await SecureStore.setItemAsync('hf_token', tokenResponse.access_token);
      return tokenResponse.access_token;
    }
    
    throw new Error('OAuth failed');
  }
  
  private async exchangeCodeForToken(code: string): Promise<any> {
    const response = await fetch('https://huggingface.co/oauth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        client_id: this.clientId,
        code,
        redirect_uri: this.redirectUri,
      }),
    });
    return response.json();
  }
}
```

**Week 23-24: UI Screens (MVP)**

**Screens to Build**:
1. **HomeScreen**: List of tasks (Chat, Ask Image, etc.)
2. **ChatScreen**: Message list + text input + send button
3. **ModelManagerScreen**: List of models with download buttons
4. **SettingsScreen**: Basic preferences (theme, token limits)

**`screens/ChatScreen.tsx`**:
```tsx
import React, { useRef, useEffect } from 'react';
import { View, FlatList, TextInput, TouchableOpacity } from 'react-native';
import { useChatStore } from '@/store/chatStore';

export const ChatScreen = () => {
  const { messages, isGenerating, sendMessage, stopGeneration } = useChatStore();
  const [input, setInput] = React.useState('');
  const flatListRef = useRef<FlatList>(null);
  
  useEffect(() => {
    // Scroll to bottom when new messages arrive
    flatListRef.current?.scrollToEnd({ animated: true });
  }, [messages]);
  
  const handleSend = async () => {
    if (!input.trim() || isGenerating) return;
    await sendMessage(input);
    setInput('');
  };
  
  return (
    <View style={{ flex: 1 }}>
      <FlatList
        ref={flatListRef}
        data={messages}
        renderItem={({ item }) => <MessageBubble message={item} />}
        keyExtractor={(item) => item.id}
      />
      
      <View style={styles.inputContainer}>
        <TextInput
          value={input}
          onChangeText={setInput}
          placeholder="Type a message..."
          editable={!isGenerating}
        />
        <TouchableOpacity onPress={isGenerating ? stopGeneration : handleSend}>
          <Text>{isGenerating ? 'Stop' : 'Send'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};
```

**Deliverable**: Functional MVP app with chat, model download, and basic settings. Ready for TestFlight/Play Store beta.

---

### **Phase 3: Feature Parity (Weeks 25-36)**

**Goal**: Port all remaining features from Android app.

**Week 25-27: Skills System**

1. **Skills Parser** (`src/skills/parser.ts`):
   - Parse `SKILL.md` frontmatter (name, description, metadata)
   - Load local skills from `FileSystem.documentDirectory + 'skills/'`
   - Fetch remote skills from URLs

2. **WebView Bridge** (`src/skills/executor.ts`):
   ```typescript
   import { WebView } from 'react-native-webview';
   
   export class SkillExecutor {
     private webViewRef = React.createRef<WebView>();
     
     async executeSkill(skillPath: string, data: any): Promise<any> {
       return new Promise((resolve, reject) => {
         // Inject skill HTML into WebView
         this.webViewRef.current?.injectJavaScript(`
           (async () => {
             try {
               const result = await window.ai_edge_gallery_get_result('${JSON.stringify(data)}');
               window.ReactNativeWebView.postMessage(result);
             } catch (e) {
               window.ReactNativeWebView.postMessage(JSON.stringify({ error: e.message }));
             }
           })();
         `);
         
         const onMessage = (event: WebViewMessageEvent) => {
           const result = JSON.parse(event.nativeEvent.data);
           if (result.error) {
             reject(new Error(result.error));
           } else {
             resolve(result);
           }
         };
         
         this.webViewRef.current?.addEventListener('message', onMessage);
       });
     }
   }
   ```

3. **Built-in Skills**: Port all skills from `skills/built-in/` (Wikipedia, QR code, interactive map, etc.)

**Week 28-30: Ask Image (Camera Integration)**

**`screens/AskImageScreen.tsx`**:
```tsx
import { Camera } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';

export const AskImageScreen = () => {
  const [hasPermission, setHasPermission] = React.useState(false);
  const cameraRef = useRef<Camera>(null);
  
  useEffect(() => {
    (async () => {
      const { status } = await Camera.requestCameraPermissionsAsync();
      setHasPermission(status === 'granted');
    })();
  }, []);
  
  const takePhoto = async () => {
    const photo = await cameraRef.current?.takePictureAsync();
    if (!photo) return;
    
    // Send image to LLM inference with multimodal support
    await useChatStore.getState().sendMessage('What is in this image?', {
      imageUri: photo.uri,
    });
  };
  
  return (
    <View style={{ flex: 1 }}>
      <Camera ref={cameraRef} style={{ flex: 1 }} />
      <TouchableOpacity onPress={takePhoto}>
        <Text>Capture</Text>
      </TouchableOpacity>
    </View>
  );
};
```

**Note**: Multimodal inference requires updating `expo-litert-lm` module to accept image tensors.

**Week 31-32: Benchmark UI**

**`screens/BenchmarkScreen.tsx`**:
```tsx
export const BenchmarkScreen = () => {
  const [results, setResults] = React.useState({
    tokensPerSecond: 0,
    firstTokenLatency: 0,
    memoryUsage: 0,
  });
  
  const runBenchmark = async () => {
    const startTime = Date.now();
    let tokenCount = 0;
    let firstTokenTime = 0;
    
    const subscription = ExpoLiteRT.addListener('onToken', (event) => {
      tokenCount++;
      if (tokenCount === 1) {
        firstTokenTime = Date.now() - startTime;
      }
    });
    
    await ExpoLiteRT.generateStream('Write a short story about AI.');
    const totalTime = Date.now() - startTime;
    
    setResults({
      tokensPerSecond: tokenCount / (totalTime / 1000),
      firstTokenLatency: firstTokenTime,
      memoryUsage: await getMemoryUsage(),
    });
    
    subscription.remove();
  };
  
  return (
    <View>
      <Button title="Run Benchmark" onPress={runBenchmark} />
      <Text>Tokens/sec: {results.tokensPerSecond.toFixed(2)}</Text>
      <Text>First token: {results.firstTokenLatency}ms</Text>
    </View>
  );
};
```

**Week 33-34: Mobile Actions / Tiny Garden**

1. **Mobile Actions**: Port function calling system
   - Define tools schema (same as Android `MobileActionsTools.kt`)
   - Implement native intents (email, SMS) with RN equivalents:
     ```typescript
     import { Linking } from 'react-native';
     import SendSMS from 'react-native-sms';
     
     export const sendEmail = (to: string, subject: string, body: string) => {
       Linking.openURL(`mailto:${to}?subject=${subject}&body=${body}`);
     };
     
     export const sendSMS = (to: string, body: string) => {
       SendSMS.send({ body, recipients: [to] });
     };
     ```

2. **Tiny Garden**: Port mini-game logic (pure JS, no native deps)

**Week 35-36: Polish & Testing**

1. **Performance Optimization**:
   - Reduce bundle size (code splitting)
   - Optimize FlatList rendering (virtualization, memo)
   - Profile with Flipper / React DevTools

2. **Accessibility**:
   - Add `accessibilityLabel` to all interactive elements
   - Test with VoiceOver (iOS) and TalkBack (Android)

3. **Error Handling**:
   - Global error boundary
   - Sentry integration for crash reporting

4. **Internationalization** (if needed):
   - `i18next` for multi-language support

**Deliverable**: Production-ready v1.0 with feature parity to Android app.

---

## 5. Dependency Mapping & Package Selection

### 5.1 Core Dependencies

| Feature | Package | Version | Notes |
|---------|---------|---------|-------|
| Framework | `expo` | ~52.0.0 | Managed workflow |
| Navigation | `@react-navigation/native` | ^6.1.18 | Stack + bottom tabs |
| State | `zustand` | ^5.0.2 | Simpler than Redux |
| Camera | `expo-camera` | ~16.0.0 | Basic capture |
| File System | `expo-file-system` | ~18.0.0 | Downloads + unzip |
| OAuth | `expo-auth-session` | ~6.0.0 | HuggingFace login |
| Secure Storage | `expo-secure-store` | ~14.0.0 | Tokens, keys |
| WebView | `react-native-webview` | ^13.12.4 | Skills execution |
| Markdown | `react-native-markdown-display` | ^7.0.2 | Chat messages |
| UI Library | `react-native-paper` | ^5.12.5 | Material Design 3 |
| Firebase | `@react-native-firebase/app` | ^21.6.0 | Analytics + FCM |

### 5.2 Optional Dependencies

| Feature | Package | When to Add |
|---------|---------|-------------|
| Audio | `expo-av` | Phase 3 (Ask Audio) |
| Image Picker | `expo-image-picker` | Phase 3 (Ask Image) |
| Email | `react-native-email-link` | Phase 3 (Mobile Actions) |
| SMS | `react-native-sms` | Phase 3 (Mobile Actions) |
| Zip | `react-native-zip-archive` | If `expo-file-system` unzip fails |
| Sentry | `@sentry/react-native` | Phase 3 (Error tracking) |

---

## 6. LiteRT-LM Native Inference Strategy

### 6.1 Technical Architecture

```
┌───────────────────────────────────────────────────────────┐
│  React Native (JavaScript)                                │
│  - UI, state management, message formatting               │
└───────────────────────────────────────────────────────────┘
                          ↓ (JS Bridge)
┌───────────────────────────────────────────────────────────┐
│  expo-litert-lm (TypeScript API)                          │
│  - loadModel(path, options)                               │
│  - generateStream(prompt) → EventEmitter                  │
│  - stopGeneration()                                       │
└───────────────────────────────────────────────────────────┘
                          ↓ (Native Module)
┌─────────────────────┬─────────────────────────────────────┐
│  Android (Kotlin)   │  iOS (Swift)                        │
│  - LiteRT-LM SDK    │  - TensorFlow Lite Swift            │
│  - TFLite GPU       │  - CoreML Delegate                  │
│  - Event emitters   │  - Metal GPU                        │
└─────────────────────┴─────────────────────────────────────┘
                          ↓
┌───────────────────────────────────────────────────────────┐
│  Model Files (.tflite / .pte)                             │
│  - Stored in FileSystem.documentDirectory                 │
│  - 2GB-8GB per model                                      │
└───────────────────────────────────────────────────────────┘
```

### 6.2 API Design

**TypeScript Interface** (`modules/expo-litert-lm/src/index.ts`):
```typescript
export interface InferenceOptions {
  maxTokens?: number;
  temperature?: number;
  topK?: number;
  topP?: number;
  stopSequences?: string[];
}

export interface TokenEvent {
  token: string;
  logProb?: number;
}

export class ExpoLiteRT {
  /**
   * Load a model from disk.
   * @throws Error if model file not found or corrupted
   */
  static async loadModel(
    modelPath: string,
    options?: InferenceOptions
  ): Promise<void>;
  
  /**
   * Generate text from prompt. Tokens are streamed via 'onToken' event.
   * @returns Promise that resolves when generation completes
   */
  static async generateStream(prompt: string): Promise<void>;
  
  /**
   * Stop ongoing generation.
   */
  static stopGeneration(): void;
  
  /**
   * Listen for token events.
   */
  static addListener(
    eventName: 'onToken',
    listener: (event: TokenEvent) => void
  ): EventSubscription;
  
  /**
   * Get model info (context length, vocab size).
   */
  static async getModelInfo(): Promise<{
    contextLength: number;
    vocabSize: number;
  }>;
  
  /**
   * Reset conversation history (clear KV cache).
   */
  static async resetConversation(): Promise<void>;
}
```

### 6.3 Android Implementation Details

**Key Classes**:
1. **`ExpoLiteRTModule.kt`**: Main Expo module, handles JS → Native calls
2. **`InferenceEngine.kt`**: Wraps LiteRT-LM SDK, manages model lifecycle
3. **`TokenStreamEmitter.kt`**: Converts LiteRT callbacks → Expo events

**Critical Implementation Notes**:
- Use coroutines for async inference (avoid blocking main thread)
- Allocate TFLite interpreter on background thread
- Stream tokens via `sendEvent()` (Expo's event emitter)
- Handle model cleanup on app backgrounding (free GPU memory)

**GPU Delegate Setup**:
```kotlin
import org.tensorflow.lite.gpu.GpuDelegate

val gpuDelegate = GpuDelegate()
val options = LlmInference.Options.Builder()
  .setDelegate(gpuDelegate)
  .setNumThreads(4)
  .build()
```

### 6.4 iOS Implementation Details

**Key Files**:
1. **`ExpoLiteRTModule.swift`**: Expo module definition
2. **`TFLiteInference.swift`**: TensorFlow Lite interpreter wrapper
3. **`TokenDecoder.swift`**: SentencePiece tokenizer integration

**iOS-Specific Challenges**:
- TFLite Swift bindings less mature than Android
- CoreML delegate requires model conversion (`.mlmodel`)
- Metal GPU setup more complex

**Alternative**: Use ONNX Runtime for iOS (better Swift support).

### 6.5 Model Format Considerations

**Current**: `.tflite` (TensorFlow Lite)  
**Alternatives**:
- **ExecuTorch** (`.pte`): Better RN community support
- **ONNX** (`.onnx`): Cross-platform, mature tooling
- **CoreML** (`.mlmodel`): iOS-native, best performance on Apple Silicon

**Recommendation**: Start with `.tflite` for Android, `.onnx` for iOS. Unify to ONNX in Phase 3 if needed.

---

## 7. HuggingFace OAuth Implementation

### 7.1 Flow Diagram

```
┌──────────┐                 ┌──────────────────┐                 ┌──────────────┐
│  User    │                 │  React Native    │                 │  HuggingFace │
└────┬─────┘                 └────────┬─────────┘                 └──────┬───────┘
     │                                │                                   │
     │  Tap "Download Gated Model"   │                                   │
     ├───────────────────────────────>│                                   │
     │                                │  AuthRequest (OAuth2)             │
     │                                ├──────────────────────────────────>│
     │                                │                                   │
     │                                │  Redirect to HF login page        │
     │                                │<──────────────────────────────────┤
     │  Enter credentials             │                                   │
     ├───────────────────────────────────────────────────────────────────>│
     │                                │                                   │
     │                                │  Authorization code               │
     │                                │<──────────────────────────────────┤
     │                                │                                   │
     │                                │  Exchange code for access token   │
     │                                ├──────────────────────────────────>│
     │                                │                                   │
     │                                │  Access token                     │
     │                                │<──────────────────────────────────┤
     │                                │                                   │
     │                                │  Store in SecureStore             │
     │                                │                                   │
     │  Download starts with token    │                                   │
     │<───────────────────────────────┤                                   │
```

### 7.2 Implementation

**`src/services/huggingface.ts`** (Full Implementation):
```typescript
import * as AuthSession from 'expo-auth-session';
import * as SecureStore from 'expo-secure-store';
import * as WebBrowser from 'expo-web-browser';

// Required for web browser to close after auth
WebBrowser.maybeCompleteAuthSession();

export class HuggingFaceAuth {
  private static readonly CLIENT_ID = 'YOUR_HF_CLIENT_ID';
  private static readonly REDIRECT_URI = AuthSession.makeRedirectUri({
    scheme: 'gallery',
    path: 'oauth/callback',
  });
  private static readonly TOKEN_KEY = 'hf_access_token';
  
  /**
   * Check if user is authenticated.
   */
  static async isAuthenticated(): Promise<boolean> {
    const token = await SecureStore.getItemAsync(this.TOKEN_KEY);
    return !!token;
  }
  
  /**
   * Get stored access token.
   */
  static async getAccessToken(): Promise<string | null> {
    return await SecureStore.getItemAsync(this.TOKEN_KEY);
  }
  
  /**
   * Initiate OAuth login flow.
   */
  static async login(): Promise<void> {
    const authRequest = new AuthSession.AuthRequest({
      clientId: this.CLIENT_ID,
      scopes: ['read-repos'],
      redirectUri: this.REDIRECT_URI,
      responseType: AuthSession.ResponseType.Code,
    });
    
    const result = await authRequest.promptAsync({
      authorizationEndpoint: 'https://huggingface.co/oauth/authorize',
    });
    
    if (result.type !== 'success') {
      throw new Error('OAuth failed: ' + result.type);
    }
    
    // Exchange authorization code for access token
    const tokenResponse = await this.exchangeCodeForToken(result.params.code);
    await SecureStore.setItemAsync(this.TOKEN_KEY, tokenResponse.access_token);
  }
  
  /**
   * Logout (clear stored token).
   */
  static async logout(): Promise<void> {
    await SecureStore.deleteItemAsync(this.TOKEN_KEY);
  }
  
  private static async exchangeCodeForToken(code: string): Promise<{
    access_token: string;
    token_type: string;
    scope: string;
  }> {
    const response = await fetch('https://huggingface.co/oauth/token', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        grant_type: 'authorization_code',
        client_id: this.CLIENT_ID,
        code,
        redirect_uri: this.REDIRECT_URI,
      }),
    });
    
    if (!response.ok) {
      throw new Error('Token exchange failed: ' + response.statusText);
    }
    
    return await response.json();
  }
  
  /**
   * Check if a model requires gated access.
   */
  static async isModelGated(modelId: string): Promise<boolean> {
    const response = await fetch(`https://huggingface.co/api/models/${modelId}`);
    const data = await response.json();
    return data.gated === 'auto' || data.gated === 'manual';
  }
}
```

### 7.3 Configuration

**`app.json` (Redirect URI Setup)**:
```json
{
  "expo": {
    "scheme": "gallery",
    "ios": {
      "bundleIdentifier": "com.google.aiedge.gallery",
      "infoPlist": {
        "CFBundleURLTypes": [
          {
            "CFBundleURLSchemes": ["gallery"]
          }
        ]
      }
    },
    "android": {
      "package": "com.google.aiedge.gallery",
      "intentFilters": [
        {
          "action": "VIEW",
          "data": [{ "scheme": "gallery" }],
          "category": ["BROWSABLE", "DEFAULT"]
        }
      ]
    }
  }
}
```

**HuggingFace App Settings**:
- **Application Name**: AI Edge Gallery
- **Homepage URL**: https://github.com/google-ai-edge/gallery
- **Redirect URLs**: 
  - `gallery://oauth/callback` (for app)
  - `https://auth.expo.io/@your-username/gallery` (for Expo Go testing)

---

## 8. Skills System Migration

### 8.1 Architecture Overview

Skills are **self-contained modules** that extend LLM capabilities. They consist of:
1. **`SKILL.md`**: Metadata + instructions for LLM
2. **`scripts/index.html`**: JavaScript logic (executed in WebView)
3. **`assets/`** (optional): WebView UI components

### 8.2 React Native Implementation

**`src/skills/types.ts`**:
```typescript
export interface SkillMetadata {
  name: string;
  description: string;
  requireSecret?: boolean;
  requireSecretDescription?: string;
  homepage?: string;
}

export interface Skill {
  metadata: SkillMetadata;
  instructions: string;
  scriptPath?: string; // Path to index.html
  assetsPath?: string; // Path to assets/ directory
}

export interface SkillResult {
  result?: string;
  error?: string;
  image?: { base64: string };
  webview?: { url: string; aspectRatio: number };
}
```

**`src/skills/loader.ts`**:
```typescript
import * as FileSystem from 'expo-file-system';
import matter from 'gray-matter';

export class SkillLoader {
  private skillsDir = FileSystem.documentDirectory + 'skills/';
  
  /**
   * Load all skills from local filesystem.
   */
  async loadLocalSkills(): Promise<Skill[]> {
    const skillDirs = await FileSystem.readDirectoryAsync(this.skillsDir);
    
    const skills = await Promise.all(
      skillDirs.map(async (dir) => {
        const skillPath = this.skillsDir + dir + '/SKILL.md';
        const content = await FileSystem.readAsStringAsync(skillPath);
        const { data: metadata, content: instructions } = matter(content);
        
        return {
          metadata: metadata as SkillMetadata,
          instructions,
          scriptPath: this.skillsDir + dir + '/scripts/index.html',
          assetsPath: this.skillsDir + dir + '/assets/',
        };
      })
    );
    
    return skills;
  }
  
  /**
   * Load a skill from a remote URL.
   */
  async loadRemoteSkill(url: string): Promise<Skill> {
    const skillUrl = url.endsWith('/') ? url : url + '/';
    const skillMdUrl = skillUrl + 'SKILL.md';
    
    const response = await fetch(skillMdUrl);
    if (!response.ok) throw new Error('Failed to fetch SKILL.md');
    
    const content = await response.text();
    const { data: metadata, content: instructions } = matter(content);
    
    return {
      metadata: metadata as SkillMetadata,
      instructions,
      scriptPath: skillUrl + 'scripts/index.html',
      assetsPath: skillUrl + 'assets/',
    };
  }
  
  /**
   * Import a skill from device storage (Android file picker).
   */
  async importSkillFromDevice(): Promise<Skill> {
    const result = await DocumentPicker.getDocumentAsync({
      type: 'text/markdown',
    });
    
    if (result.type === 'cancel') throw new Error('Cancelled');
    
    // Copy skill directory to app's skill folder
    const targetDir = this.skillsDir + Date.now() + '/';
    await FileSystem.copyAsync({
      from: result.uri,
      to: targetDir,
    });
    
    return this.loadLocalSkills().then((skills) => skills[skills.length - 1]);
  }
}
```

**`src/skills/executor.ts`**:
```typescript
import React from 'react';
import { WebView } from 'react-native-webview';

export class SkillExecutor {
  private webViewRef: React.RefObject<WebView>;
  
  constructor(webViewRef: React.RefObject<WebView>) {
    this.webViewRef = webViewRef;
  }
  
  /**
   * Execute a JavaScript skill.
   */
  async executeSkill(
    skill: Skill,
    data: any,
    secret?: string
  ): Promise<SkillResult> {
    if (!skill.scriptPath) {
      throw new Error('Skill has no script');
    }
    
    return new Promise((resolve, reject) => {
      const timeoutId = setTimeout(() => {
        reject(new Error('Skill execution timeout'));
      }, 30000); // 30s timeout
      
      // Inject JavaScript to call skill function
      const injectedJS = `
        (async () => {
          try {
            const result = await window.ai_edge_gallery_get_result(
              '${JSON.stringify(data)}',
              '${secret || ''}'
            );
            window.ReactNativeWebView.postMessage(result);
          } catch (e) {
            window.ReactNativeWebView.postMessage(JSON.stringify({
              error: e.message
            }));
          }
        })();
        true; // Required for iOS
      `;
      
      this.webViewRef.current?.injectJavaScript(injectedJS);
      
      const handleMessage = (event: any) => {
        clearTimeout(timeoutId);
        const result = JSON.parse(event.nativeEvent.data);
        
        if (result.error) {
          reject(new Error(result.error));
        } else {
          resolve(result as SkillResult);
        }
      };
      
      // Listen for WebView message
      this.webViewRef.current?.addEventListener('message', handleMessage);
    });
  }
}
```

**`screens/AgentChatScreen.tsx`** (Using Skills):
```tsx
import React, { useRef } from 'react';
import { WebView } from 'react-native-webview';

export const AgentChatScreen = () => {
  const webViewRef = useRef<WebView>(null);
  const skillExecutor = new SkillExecutor(webViewRef);
  const [skills, setSkills] = React.useState<Skill[]>([]);
  
  useEffect(() => {
    // Load skills on mount
    const loader = new SkillLoader();
    loader.loadLocalSkills().then(setSkills);
  }, []);
  
  const handleToolCall = async (toolName: string, args: any) => {
    if (toolName === 'run_js') {
      const skill = skills.find((s) => s.metadata.name === args.skillName);
      if (!skill) throw new Error('Skill not found');
      
      const result = await skillExecutor.executeSkill(skill, args.data);
      return result;
    }
  };
  
  return (
    <>
      {/* Hidden WebView for skill execution */}
      <WebView
        ref={webViewRef}
        source={{ uri: 'about:blank' }}
        style={{ height: 0, width: 0 }}
      />
      
      {/* Chat UI */}
      <ChatScreen onToolCall={handleToolCall} />
    </>
  );
};
```

### 8.3 Built-in Skills Compatibility

All existing Android skills should work identically:

| Skill Name | Type | Compatibility | Notes |
|-----------|------|---------------|-------|
| kitchen-adventure | Text-only | ✅ Perfect | Pure persona, no code |
| calculate-hash | JS | ✅ Perfect | WebCrypto API works in RN WebView |
| query-wikipedia | JS + API | ✅ Perfect | `fetch()` works identically |
| qr-code | JS + Image | ✅ Perfect | Canvas API + toDataURL() supported |
| interactive-map | JS + WebView | ✅ Perfect | Leaflet.js works in nested WebView |
| mood-tracker | JS + WebView + LocalStorage | ✅ Perfect | LocalStorage persists in WebView |
| send-email | Native | ⚠️ Needs adapter | Replace with `react-native-email-link` |
| send-sms | Native | ⚠️ Needs adapter | Replace with `react-native-sms` |

**Native Intent Adapters** (`src/skills/nativeIntents.ts`):
```typescript
import { Linking } from 'react-native';
import SendSMS from 'react-native-sms';

export const nativeIntents = {
  send_email: async (params: { extra_email: string; extra_subject: string; extra_text: string }) => {
    const url = `mailto:${params.extra_email}?subject=${encodeURIComponent(params.extra_subject)}&body=${encodeURIComponent(params.extra_text)}`;
    await Linking.openURL(url);
  },
  
  send_sms: async (params: { address: string; body: string }) => {
    await SendSMS.send({
      body: params.body,
      recipients: [params.address],
    });
  },
};
```

---

## 9. Model Download & File Management

### 9.1 Download Manager Architecture

```typescript
// src/services/downloadManager.ts
import * as FileSystem from 'expo-file-system';
import * as BackgroundFetch from 'expo-background-fetch';
import * as TaskManager from 'expo-task-manager';

const DOWNLOAD_TASK_NAME = 'model-download-task';

export class DownloadManager {
  private modelDir = FileSystem.documentDirectory + 'models/';
  private activeDownloads = new Map<string, FileSystem.DownloadResumable>();
  
  /**
   * Download a model file.
   */
  async downloadModel(
    modelId: string,
    url: string,
    fileName: string,
    accessToken?: string,
    onProgress?: (percent: number, speed: number) => void
  ): Promise<string> {
    // Ensure model directory exists
    await this.ensureDirectoryExists(this.modelDir);
    
    const downloadPath = this.modelDir + fileName;
    const downloadResumable = FileSystem.createDownloadResumable(
      url,
      downloadPath,
      {
        headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
      },
      (downloadProgress) => {
        const { totalBytesWritten, totalBytesExpectedToWrite } = downloadProgress;
        const percent = totalBytesWritten / totalBytesExpectedToWrite;
        
        // Calculate download speed (bytes/sec)
        const now = Date.now();
        const elapsed = (now - this.startTime) / 1000; // seconds
        const speed = totalBytesWritten / elapsed;
        
        onProgress?.(percent, speed);
      }
    );
    
    this.activeDownloads.set(modelId, downloadResumable);
    this.startTime = Date.now();
    
    try {
      const result = await downloadResumable.downloadAsync();
      if (!result) throw new Error('Download failed');
      
      // Unzip if .zip file
      if (fileName.endsWith('.zip')) {
        const unzipDir = this.modelDir + modelId + '/';
        await FileSystem.unzipAsync(result.uri, unzipDir);
        return unzipDir;
      }
      
      return result.uri;
    } finally {
      this.activeDownloads.delete(modelId);
    }
  }
  
  /**
   * Pause/resume download.
   */
  async pauseDownload(modelId: string): Promise<void> {
    const download = this.activeDownloads.get(modelId);
    if (!download) throw new Error('Download not found');
    
    const resumeData = await download.pauseAsync();
    // Store resumeData for later resume
    await AsyncStorage.setItem(`resume_${modelId}`, JSON.stringify(resumeData));
  }
  
  async resumeDownload(modelId: string, onProgress?: (percent: number) => void): Promise<string> {
    const resumeDataStr = await AsyncStorage.getItem(`resume_${modelId}`);
    if (!resumeDataStr) throw new Error('No resume data found');
    
    const resumeData = JSON.parse(resumeDataStr);
    const downloadResumable = new FileSystem.DownloadResumable(
      resumeData.url,
      resumeData.fileUri,
      resumeData.options,
      (progress) => {
        const percent = progress.totalBytesWritten / progress.totalBytesExpectedToWrite;
        onProgress?.(percent);
      },
      resumeData.resumeData
    );
    
    const result = await downloadResumable.resumeAsync();
    if (!result) throw new Error('Resume failed');
    
    await AsyncStorage.removeItem(`resume_${modelId}`);
    return result.uri;
  }
  
  /**
   * Cancel download.
   */
  async cancelDownload(modelId: string): Promise<void> {
    const download = this.activeDownloads.get(modelId);
    if (download) {
      await download.pauseAsync(); // Pause first to get cleanup
      this.activeDownloads.delete(modelId);
    }
    
    // Delete partial file
    const fileName = `${modelId}.tflite`;
    const filePath = this.modelDir + fileName;
    await FileSystem.deleteAsync(filePath, { idempotent: true });
  }
  
  /**
   * List downloaded models.
   */
  async getDownloadedModels(): Promise<string[]> {
    const files = await FileSystem.readDirectoryAsync(this.modelDir);
    return files.filter((f) => f.endsWith('.tflite') || f.endsWith('.onnx'));
  }
  
  /**
   * Delete a model file.
   */
  async deleteModel(fileName: string): Promise<void> {
    const filePath = this.modelDir + fileName;
    await FileSystem.deleteAsync(filePath);
  }
  
  private async ensureDirectoryExists(dir: string): Promise<void> {
    const info = await FileSystem.getInfoAsync(dir);
    if (!info.exists) {
      await FileSystem.makeDirectoryAsync(dir, { intermediates: true });
    }
  }
}
```

### 9.2 Background Download Support (iOS/Android)

**iOS Limitations**:
- Background downloads limited to 30s after app backgrounding
- Use `expo-task-manager` + `expo-background-fetch` to extend time

**Setup**:
```typescript
// Register background task
TaskManager.defineTask(DOWNLOAD_TASK_NAME, async () => {
  try {
    // Resume any paused downloads
    const modelIds = await getInProgressDownloads();
    for (const id of modelIds) {
      await downloadManager.resumeDownload(id);
    }
    
    return BackgroundFetch.BackgroundFetchResult.NewData;
  } catch (error) {
    return BackgroundFetch.BackgroundFetchResult.Failed;
  }
});

// Register background fetch
BackgroundFetch.registerTaskAsync(DOWNLOAD_TASK_NAME, {
  minimumInterval: 60 * 15, // 15 minutes
  stopOnTerminate: false,
  startOnBoot: true,
});
```

### 9.3 Model Storage Structure

```
FileSystem.documentDirectory/
└── models/
    ├── gemma_2b_it_q8/
    │   ├── gemma-2b-it-q8.tflite
    │   └── tokenizer.json
    ├── gemma_4_it_q8/
    │   ├── gemma-4-it-q8.tflite
    │   └── tokenizer.json
    └── .downloads/               # Temporary download staging
        └── gemma_2b_it_q8.zip.part
```

---

## 10. Camera Integration (Ask Image)

### 10.1 Basic Camera Capture

**`screens/AskImageScreen.tsx`**:
```tsx
import { Camera, CameraType } from 'expo-camera';
import * as ImageManipulator from 'expo-image-manipulator';

export const AskImageScreen = () => {
  const [hasPermission, setHasPermission] = React.useState(false);
  const [cameraType, setCameraType] = React.useState(CameraType.back);
  const cameraRef = useRef<Camera>(null);
  
  useEffect(() => {
    (async () => {
      const { status } = await Camera.requestCameraPermissionsAsync();
      setHasPermission(status === 'granted');
    })();
  }, []);
  
  const captureAndAsk = async () => {
    if (!cameraRef.current) return;
    
    const photo = await cameraRef.current.takePictureAsync({
      quality: 0.8,
      base64: true,
    });
    
    // Resize image to max 1024x1024 (LLM input size)
    const resized = await ImageManipulator.manipulateAsync(
      photo.uri,
      [{ resize: { width: 1024 } }],
      { compress: 0.8, format: ImageManipulator.SaveFormat.JPEG }
    );
    
    // Send to LLM inference with image
    await useChatStore.getState().sendMessage('What is in this image?', {
      imageUri: resized.uri,
      imageBase64: resized.base64,
    });
    
    // Navigate back to chat
    navigation.goBack();
  };
  
  if (!hasPermission) {
    return <Text>Camera permission required</Text>;
  }
  
  return (
    <View style={{ flex: 1 }}>
      <Camera ref={cameraRef} style={{ flex: 1 }} type={cameraType} />
      
      <View style={styles.controls}>
        <TouchableOpacity onPress={() => setCameraType(
          cameraType === CameraType.back ? CameraType.front : CameraType.back
        )}>
          <Text>Flip</Text>
        </TouchableOpacity>
        
        <TouchableOpacity onPress={captureAndAsk}>
          <Text>Capture & Ask</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};
```

### 10.2 Multimodal Inference

**Update `expo-litert-lm` to accept images**:

**TypeScript API**:
```typescript
export interface MultimodalInput {
  text: string;
  images?: string[]; // Array of base64-encoded images
}

export class ExpoLiteRT {
  static async generateStreamMultimodal(
    input: MultimodalInput
  ): Promise<void> {
    // Native module handles image encoding → tensor conversion
  }
}
```

**Android Implementation** (`ExpoLiteRTModule.kt`):
```kotlin
@ExpoMethod
fun generateStreamMultimodal(input: Map<String, Any>, promise: Promise) {
  val text = input["text"] as String
  val imagesBase64 = input["images"] as? List<String> ?: emptyList()
  
  // Decode base64 → Bitmap
  val bitmaps = imagesBase64.map { base64 ->
    val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
  }
  
  // Pass to LiteRT-LM with image inputs
  llmInference.generateResponseAsync(
    prompt = text,
    images = bitmaps
  ) { partialResponse ->
    sendEvent("onToken", mapOf("token" to partialResponse))
  }
}
```

---

## 11. State Management Architecture

### 11.1 Zustand Store Design

**Store Structure**:
```
src/store/
├── chatStore.ts        # Chat messages, send/receive
├── modelStore.ts       # Model list, active model, loading state
├── downloadStore.ts    # Download queue, progress tracking
├── settingsStore.ts    # User preferences, theme
└── skillsStore.ts      # Loaded skills, skill execution state
```

### 11.2 Example: Chat Store

**`src/store/chatStore.ts`**:
```typescript
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  imageUri?: string;
  thinking?: string; // For thinking mode
}

interface Conversation {
  id: string;
  title: string;
  messages: Message[];
  createdAt: number;
  updatedAt: number;
}

interface ChatState {
  // State
  conversations: Conversation[];
  activeConversationId: string | null;
  isGenerating: boolean;
  
  // Computed
  activeConversation: Conversation | null;
  
  // Actions
  createConversation: () => string;
  deleteConversation: (id: string) => void;
  setActiveConversation: (id: string) => void;
  sendMessage: (text: string, options?: { imageUri?: string }) => Promise<void>;
  stopGeneration: () => void;
  clearHistory: () => void;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      // Initial state
      conversations: [],
      activeConversationId: null,
      isGenerating: false,
      
      // Computed (use selector pattern)
      get activeConversation() {
        const { conversations, activeConversationId } = get();
        return conversations.find((c) => c.id === activeConversationId) || null;
      },
      
      // Actions
      createConversation: () => {
        const id = uuid();
        const newConversation: Conversation = {
          id,
          title: 'New Chat',
          messages: [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
        };
        
        set((state) => ({
          conversations: [...state.conversations, newConversation],
          activeConversationId: id,
        }));
        
        return id;
      },
      
      deleteConversation: (id: string) => {
        set((state) => ({
          conversations: state.conversations.filter((c) => c.id !== id),
          activeConversationId:
            state.activeConversationId === id ? null : state.activeConversationId,
        }));
      },
      
      setActiveConversation: (id: string) => {
        set({ activeConversationId: id });
      },
      
      sendMessage: async (text: string, options = {}) => {
        const { activeConversation } = get();
        if (!activeConversation) {
          throw new Error('No active conversation');
        }
        
        const userMessage: Message = {
          id: uuid(),
          role: 'user',
          content: text,
          timestamp: Date.now(),
          imageUri: options.imageUri,
        };
        
        // Add user message immediately
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === activeConversation.id
              ? { ...c, messages: [...c.messages, userMessage] }
              : c
          ),
          isGenerating: true,
        }));
        
        // Start LLM inference
        let assistantText = '';
        const assistantMessage: Message = {
          id: uuid(),
          role: 'assistant',
          content: '',
          timestamp: Date.now(),
        };
        
        // Add placeholder assistant message
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === activeConversation.id
              ? { ...c, messages: [...c.messages, assistantMessage] }
              : c
          ),
        }));
        
        // Listen for token stream
        const subscription = ExpoLiteRT.addListener('onToken', (event) => {
          assistantText += event.token;
          
          // Update assistant message in real-time
          set((state) => ({
            conversations: state.conversations.map((c) =>
              c.id === activeConversation.id
                ? {
                    ...c,
                    messages: c.messages.map((m) =>
                      m.id === assistantMessage.id
                        ? { ...m, content: assistantText }
                        : m
                    ),
                    updatedAt: Date.now(),
                  }
                : c
            ),
          }));
        });
        
        try {
          await ExpoLiteRT.generateStream(text);
          subscription.remove();
          
          // Auto-generate conversation title from first message
          if (activeConversation.messages.length === 0) {
            const title = text.slice(0, 30) + (text.length > 30 ? '...' : '');
            set((state) => ({
              conversations: state.conversations.map((c) =>
                c.id === activeConversation.id ? { ...c, title } : c
              ),
            }));
          }
        } catch (error) {
          console.error('Inference error:', error);
        } finally {
          subscription.remove();
          set({ isGenerating: false });
        }
      },
      
      stopGeneration: () => {
        ExpoLiteRT.stopGeneration();
        set({ isGenerating: false });
      },
      
      clearHistory: () => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === state.activeConversationId ? { ...c, messages: [] } : c
          ),
        }));
      },
    }),
    {
      name: 'chat-storage',
      storage: createJSONStorage(() => AsyncStorage),
      // Only persist conversations, not isGenerating
      partialize: (state) => ({
        conversations: state.conversations,
        activeConversationId: state.activeConversationId,
      }),
    }
  )
);
```

---

## 12. UI Migration Guide

### 12.1 Component Mapping

| Android (Compose) | React Native | Notes |
|------------------|--------------|-------|
| `Column` | `<View style={{ flexDirection: 'column' }}>` | Default flex direction |
| `Row` | `<View style={{ flexDirection: 'row' }}>` | Horizontal layout |
| `Text` | `<Text>` | Similar API |
| `TextField` | `<TextInput>` | Built-in component |
| `Button` | `<TouchableOpacity>` + `<Text>` | More flexible |
| `LazyColumn` | `<FlatList>` | Virtualized list |
| `Image` | `<Image>` or `<FastImage>` | Use `react-native-fast-image` for performance |
| `Scaffold` | `<SafeAreaView>` + layout | SafeAreaView handles notches |
| `TopAppBar` | `<View>` + `react-navigation` header | Built into navigator |
| `BottomNavigation` | `@react-navigation/bottom-tabs` | Navigator handles tabs |
| `AlertDialog` | `Alert.alert()` or `react-native-paper` Dialog | Native alert or custom |
| `CircularProgressIndicator` | `<ActivityIndicator>` | Built-in |

### 12.2 Navigation Setup

**`src/navigation/RootNavigator.tsx`**:
```tsx
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';

const Stack = createNativeStackNavigator();
const Tab = createBottomTabNavigator();

function HomeTabs() {
  return (
    <Tab.Navigator>
      <Tab.Screen name="Home" component={HomeScreen} />
      <Tab.Screen name="Models" component={ModelManagerScreen} />
      <Tab.Screen name="Settings" component={SettingsScreen} />
    </Tab.Navigator>
  );
}

export function RootNavigator() {
  return (
    <NavigationContainer>
      <Stack.Navigator>
        <Stack.Screen name="Main" component={HomeTabs} options={{ headerShown: false }} />
        <Stack.Screen name="Chat" component={ChatScreen} />
        <Stack.Screen name="AskImage" component={AskImageScreen} />
        <Stack.Screen name="Benchmark" component={BenchmarkScreen} />
        <Stack.Screen name="AgentChat" component={AgentChatScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
```

### 12.3 Theming

**`src/theme/theme.ts`**:
```typescript
import { MD3LightTheme, MD3DarkTheme } from 'react-native-paper';

export const lightTheme = {
  ...MD3LightTheme,
  colors: {
    ...MD3LightTheme.colors,
    primary: '#6750A4',
    secondary: '#625B71',
    background: '#FFFBFE',
  },
};

export const darkTheme = {
  ...MD3DarkTheme,
  colors: {
    ...MD3DarkTheme.colors,
    primary: '#D0BCFF',
    secondary: '#CCC2DC',
    background: '#1C1B1F',
  },
};
```

**Usage**:
```tsx
import { Provider as PaperProvider } from 'react-native-paper';
import { useColorScheme } from 'react-native';

export default function App() {
  const colorScheme = useColorScheme();
  const theme = colorScheme === 'dark' ? darkTheme : lightTheme;
  
  return (
    <PaperProvider theme={theme}>
      <RootNavigator />
    </PaperProvider>
  );
}
```

---

## 13. Testing Strategy

### 13.1 Unit Tests (Jest)

**Test Zustand Stores**:
```typescript
// __tests__/chatStore.test.ts
import { renderHook, act } from '@testing-library/react-hooks';
import { useChatStore } from '@/store/chatStore';

jest.mock('@/modules/expo-litert-lm', () => ({
  ExpoLiteRT: {
    generateStream: jest.fn(() => Promise.resolve()),
    addListener: jest.fn(() => ({ remove: jest.fn() })),
  },
}));

describe('chatStore', () => {
  it('should create a new conversation', () => {
    const { result } = renderHook(() => useChatStore());
    
    act(() => {
      result.current.createConversation();
    });
    
    expect(result.current.conversations).toHaveLength(1);
    expect(result.current.activeConversationId).toBe(result.current.conversations[0].id);
  });
  
  it('should add user message on sendMessage', async () => {
    const { result } = renderHook(() => useChatStore());
    
    act(() => {
      result.current.createConversation();
    });
    
    await act(async () => {
      await result.current.sendMessage('Hello');
    });
    
    expect(result.current.activeConversation?.messages).toHaveLength(2); // user + assistant
    expect(result.current.activeConversation?.messages[0].content).toBe('Hello');
  });
});
```

### 13.2 Integration Tests (Detox)

**Setup**:
```bash
npm install --save-dev detox jest
npx detox init
```

**`e2e/chatFlow.test.ts`**:
```typescript
describe('Chat Flow', () => {
  beforeAll(async () => {
    await device.launchApp();
  });
  
  it('should send a message and receive response', async () => {
    // Navigate to chat
    await element(by.text('AI Chat')).tap();
    
    // Type message
    await element(by.id('chat-input')).typeText('Hello, AI!');
    await element(by.id('send-button')).tap();
    
    // Wait for response
    await waitFor(element(by.text('Hello!')))
      .toBeVisible()
      .withTimeout(10000);
    
    // Verify message appears
    await expect(element(by.text('Hello, AI!'))).toBeVisible();
  });
});
```

### 13.3 Performance Tests

**Benchmark Inference Speed**:
```typescript
describe('Inference Performance', () => {
  it('should generate at least 10 tokens/sec', async () => {
    const startTime = Date.now();
    let tokenCount = 0;
    
    ExpoLiteRT.addListener('onToken', () => {
      tokenCount++;
    });
    
    await ExpoLiteRT.generateStream('Write a short story.');
    const elapsed = (Date.now() - startTime) / 1000;
    const tokensPerSec = tokenCount / elapsed;
    
    expect(tokensPerSec).toBeGreaterThan(10);
  });
});
```

---

## 14. Performance Benchmarks & Targets

### 14.1 Target Metrics (vs Android Native)

| Metric | Android Native | RN Target | Acceptable Range |
|--------|---------------|-----------|------------------|
| Tokens/sec (Gemma 2B, iPhone 15 Pro) | 25 | 20 | 15-25 |
| Tokens/sec (Gemma 2B, Pixel 8) | 22 | 18 | 14-22 |
| First token latency | 200ms | 300ms | 200-400ms |
| Memory usage (idle) | 150MB | 180MB | 150-200MB |
| Memory usage (inference) | 2.5GB | 2.8GB | 2.5-3.5GB |
| App launch time | 1.2s | 1.5s | 1.0-2.0s |
| Model load time (2B) | 3s | 4s | 3-5s |

### 14.2 Optimization Strategies

**If Performance Falls Short**:
1. **Enable Hermes**: Faster JS execution (built-in with Expo SDK 52)
2. **GPU Delegate**: Ensure TFLite GPU delegate is active on iOS/Android
3. **Reduce Bridge Overhead**: Batch token updates (send every 5 tokens, not every token)
4. **Quantize Models**: Use INT8 quantization for faster inference
5. **Profile with Flamegraph**: Identify JS bottlenecks

**Example: Batched Token Updates**:
```kotlin
// Android: Batch tokens to reduce bridge calls
val tokenBuffer = mutableListOf<String>()

llmInference.generateResponseAsync(prompt) { token ->
  tokenBuffer.add(token)
  
  if (tokenBuffer.size >= 5 || isDone) {
    sendEvent("onTokens", mapOf("tokens" to tokenBuffer.joinToString("")))
    tokenBuffer.clear()
  }
}
```

---

## 15. MVP Scope Definition

### 15.1 Must-Have Features (MVP)

**Core Functionality**:
1. ✅ **Chat UI**: Text-only chat with one model (Gemma 2B)
2. ✅ **Inference**: Stream tokens from LLM (via custom Expo module)
3. ✅ **Conversation History**: Save/load chat sessions (AsyncStorage)
4. ✅ **Model Loading**: Load pre-downloaded .tflite model from disk
5. ✅ **Basic Settings**: Temperature, top-k sliders

**Excluded from MVP**:
- ❌ Model downloads (manually sideload models)
- ❌ HuggingFace OAuth (no gated models)
- ❌ Skills system
- ❌ Camera (Ask Image)
- ❌ Benchmark UI
- ❌ Multiple models
- ❌ Thinking mode

**Timeline**: 12-16 weeks (3-4 months)

### 15.2 Post-MVP Roadmap

**Phase 4: Advanced Features** (Months 4-6)
- ✅ Model downloads + HuggingFace OAuth
- ✅ Model manager UI
- ✅ Agent Skills system
- ✅ Ask Image (camera integration)
- ✅ Multiple models support

**Phase 5: Polish** (Month 6-9)
- ✅ Benchmark UI
- ✅ Thinking mode
- ✅ Mobile Actions / Tiny Garden
- ✅ Performance optimization
- ✅ Accessibility improvements

---

## Conclusion

This migration plan provides a **structured, phase-by-phase approach** to porting the Google AI Edge Gallery Android app to React Native + Expo. The most critical path is **Phase 1 (LLM Inference Module)**, which blocks all other features. By following this plan, you can achieve:

- ✅ **MVP in 3-4 months** (chat-only, manual model loading)
- ✅ **Full feature parity in 6-9 months** (all tasks, downloads, skills)
- ✅ **Cross-platform support** (iOS + Android)
- ✅ **Maintainable architecture** (Zustand, Expo modules, TypeScript)

**Next Steps**:
1. Validate PoC (Phase 0) with WebAssembly inference
2. Assign native engineer to Phase 1 (Expo module development)
3. Parallelize UI work (Phase 2) while inference module is in progress
4. Test early and often on real devices (not simulators)

Good luck! 🚀
