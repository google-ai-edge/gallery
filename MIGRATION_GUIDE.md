# Neural Forge Migration Guide

This guide explains how to complete the transformation from Google AI Edge Gallery to Neural Forge.

## Phase 1 Completion Status ✅

Phase 1 has established the foundation for Neural Forge:

### Completed
- ✅ New package namespace: `com.neuralforge.mobile`
- ✅ Updated `build.gradle.kts` with new dependencies (ONNX, OkHttp, Lottie, Coil)
- ✅ Updated `AndroidManifest.xml` with Neural Forge branding
- ✅ Created core Neural Forge components:
  - `NeuralForgeEngine` - Central orchestrator
  - `ModelDownloadManager` - Enhanced download system
  - `ModelFormatDetector` - Format detection system
  - `ONNXInferenceEngine` - ONNX Runtime integration
  - `ModelConverter` - Conversion infrastructure
- ✅ Updated README with Neural Forge branding and roadmap
- ✅ Created comprehensive architecture documentation (NEURAL_FORGE.md)

### Remaining Work

#### 1. Package Refactoring (Critical)

**ALL existing Kotlin files need package declarations updated:**

```kotlin
// OLD (400+ files to update)
package com.google.ai.edge.gallery.*

// NEW
package com.neuralforge.mobile.*
```

**Recommended Approach:**

**Option A: Android Studio (Recommended)**
1. Open project in Android Studio
2. Right-click on `com.google.ai.edge.gallery` package in Project view
3. Select Refactor → Rename
4. Enter new package name: `com.neuralforge.mobile`
5. Click "Refactor" and review changes
6. Android Studio will update all references automatically

**Option B: Command Line (For experts)**
```bash
# Navigate to source directory
cd Android/src/app/src/main/java

# Rename package directories
mv com/google/ai/edge/gallery com/neuralforge/mobile

# Update all package declarations
find . -name "*.kt" -exec sed -i 's/package com.google.ai.edge.gallery/package com.neuralforge.mobile/g' {} +

# Update all imports
find . -name "*.kt" -exec sed -i 's/import com.google.ai.edge.gallery/import com.neuralforge.mobile/g' {} +
```

#### 2. Resource Files Updates

Update XML resources:

**strings.xml** (`app/src/main/res/values/strings.xml`):
```xml
<!-- Update app name -->
<string name="app_name">Neural Forge</string>

<!-- Update all user-facing strings with Neural Forge branding -->
```

**themes.xml** (if exists):
```xml
<!-- Update theme names from Gallery to NeuralForge -->
```

#### 3. Dependency Integration

The new components are standalone and need to be integrated:

**Create Hilt Module** (`di/NeuralForgeModule.kt`):
```kotlin
package com.neuralforge.mobile.di

import android.content.Context
import com.neuralforge.mobile.core.NeuralForgeEngine
import com.neuralforge.mobile.downloader.ModelDownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NeuralForgeModule {

    @Provides
    @Singleton
    fun provideModelDownloadManager(
        @ApplicationContext context: Context
    ): ModelDownloadManager {
        return ModelDownloadManager(context)
    }

    @Provides
    @Singleton
    fun provideNeuralForgeEngine(
        @ApplicationContext context: Context,
        downloadManager: ModelDownloadManager,
        formatDetector: ModelFormatDetector,
        modelConverter: ModelConverter,
        onnxEngine: ONNXInferenceEngine
    ): NeuralForgeEngine {
        return NeuralForgeEngine(
            context,
            downloadManager,
            formatDetector,
            modelConverter,
            onnxEngine
        )
    }
}
```

#### 4. Application Class Update

Update `GalleryApplication.kt` → `NeuralForgeApplication.kt`:

```kotlin
package com.neuralforge.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NeuralForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Neural Forge Engine
        // Set up crash reporting, analytics, etc.
    }
}
```

#### 5. MainActivity Update

Update `MainActivity.kt`:
```kotlin
package com.neuralforge.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NeuralForgeApp() // Update from GalleryApp
        }
    }
}
```

#### 6. Build Configuration

Update `settings.gradle.kts` (if needed):
```kotlin
rootProject.name = "NeuralForge"
```

#### 7. Integration Testing

After package refactoring:

```bash
# Clean build
cd Android/src
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Phase 2 Preview

Once Phase 1 integration is complete, Phase 2 will add:

1. **Model Conversion Implementation**
   - ONNX to TFLite converter
   - PyTorch to ONNX converter
   - Quantization pipeline

2. **Enhanced UI**
   - Lottie animations for loading states
   - Improved model cards with Coil image loading
   - Model marketplace UI

3. **Advanced Download Features**
   - Split model download implementation
   - Background download service
   - Download queue management

4. **GPU Acceleration**
   - ONNX GPU delegate
   - TFLite GPU delegate optimization
   - Hardware capability detection

## Testing Checklist

After completing package refactoring:

- [ ] App builds without errors
- [ ] App installs successfully
- [ ] App launches without crashes
- [ ] Splash screen shows "Neural Forge"
- [ ] Model download works
- [ ] Format detection works
- [ ] ONNX model loading works
- [ ] Existing TFLite functionality works
- [ ] Chat interface works
- [ ] Image processing works
- [ ] Audio processing works

## Troubleshooting

### Build Errors

**Problem**: "Cannot resolve symbol 'gallery'"
**Solution**: Package refactoring incomplete. Check all imports.

**Problem**: "Unresolved reference: GalleryApplication"
**Solution**: Update AndroidManifest.xml with new class name.

### Runtime Errors

**Problem**: ClassNotFoundException
**Solution**: Verify ProGuard rules if using minification.

**Problem**: ONNX Runtime not found
**Solution**: Check that ONNX dependency is correctly included.

## Getting Help

For issues during migration:
1. Check build logs: `./gradlew build --stacktrace`
2. Verify all imports are updated
3. Clean and rebuild: `./gradlew clean build`

## Next Steps

1. Complete package refactoring using Android Studio
2. Update Application class
3. Create Hilt modules for new components
4. Build and test thoroughly
5. Commit changes
6. Move to Phase 2!

---

**Ready to transform your device into a Neural Forge!** 🔥
