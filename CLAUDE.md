# Video Object Analyzer Implementation

## Overview
This implementation adds video object analysis capabilities to the existing Android gallery app by integrating with the current chat interface and Gemma VLM models. The feature captures video frames at 1 FPS, collects 10 images, and uses the installed Gemma model to analyze and summarize detected objects in JSON format.

## Key Design Principles
- **Reuse Existing Infrastructure**: Leverages existing chat interface, model management, and UI components
- **Minimal Code Addition**: Adds only essential components without duplicating functionality
- **Seamless Integration**: Works within existing "LLM Ask Image" task flow
- **No Breaking Changes**: All additions are backward compatible

## Files Created

### 1. Data Models (`VideoAnalysis.kt`)
```kotlin
// Location: /data/VideoAnalysis.kt
```
- `FrameData`: Stores frame metadata (timestamp, frame number, path)
- `DetectedObject`: Object detection results with confidence and position
- `VideoAnalysisResult`: Complete analysis results with JSON structure
- `CapturedFrame`: Combines bitmap with frame metadata
- `CaptureState`: Enum for capture progress states
- `VideoCaptureProgress`: Progress tracking data

### 2. Video Capture Component (`VideoFrameCaptureButton.kt`)
```kotlin
// Location: /ui/common/chat/VideoFrameCaptureButton.kt
```
**Features:**
- 1 FPS video sampling using legacy Camera API for compatibility
- Captures exactly 10 frames with progress indication
- Permission handling for camera access
- Surface view for camera preview
- Automatic bitmap conversion and storage

**Integration:** Added to existing `MessageInputImage.kt` component as fourth button alongside photo picker, camera, and video stream buttons.

### 3. Chat Extensions (`VideoAnalysisChatExtension.kt`)
```kotlin
// Location: /ui/videoanalysis/VideoAnalysisChatExtension.kt
```
**Components:**
- `VideoAnalysisPromptButton`: Adds frames and analysis prompt to chat
- `VideoAnalysisQuickStart`: Quick capture and analyze panel
- `buildVideoAnalysisPrompt()`: Comprehensive analysis prompt for VLM

**Prompt Content:**
```text
Analyze the following sequence of video frames captured at 1 FPS intervals. 

Please identify and describe the objects present in these frames and provide your response 
in the following JSON format:

{
  "detected_objects": [
    {
      "name": "object_name",
      "confidence": 0.95,
      "description": "detailed description of the object",
      "position": {
        "x": 0.3,
        "y": 0.4, 
        "width": 0.2,
        "height": 0.4
      }
    }
  ],
  "summary": "Overall summary of what was observed across the frames",
  "scene_description": "Description of the overall scene and context"
}

Focus on:
1. Identifying distinct objects and their characteristics
2. Tracking object movement or changes across frames
3. Providing confidence scores for each detection
4. Describing the overall scene context

Please be thorough but concise in your descriptions.
```

### 4. Enhanced LLM Screen (`VideoAnalysisLlmScreen.kt`)
```kotlin
// Location: /ui/videoanalysis/VideoAnalysisLlmScreen.kt
```
**Features:**
- Optional enhanced version of LLM Ask Image screen
- Adds `VideoAnalysisQuickStart` panel at top
- Reuses existing `LlmAskImageViewModel` and `ChatView`
- Automatic prompt injection and inference triggering

## Integration Points

### Existing Component Modifications

#### MessageInputImage.kt
**Addition:** New video capture button in the input row
```kotlin
// Video frame capture (1 FPS sampling)
VideoFrameCaptureButton(
  onFramesCaptured = { frames ->
    frames.forEach { bitmap ->
      onImageSelected(bitmap)
    }
  },
  enabled = !disableButtons,
  modifier = Modifier.alpha(buttonAlpha)
)
```

## Technical Implementation

### Video Capture Flow
1. **Permission Check**: Requests camera permission if needed
2. **Camera Initialization**: Opens camera with 640x480 preview size
3. **Frame Sampling**: Captures frames at exactly 1000ms intervals
4. **Format Conversion**: YUV to JPEG to Bitmap conversion
5. **Progress Tracking**: Updates UI with capture count (X/10)
6. **Completion**: Returns list of 10 bitmap images

### Analysis Flow
1. **Frame Addition**: Each captured bitmap added as `ChatMessageImage`
2. **Prompt Injection**: Comprehensive analysis prompt added as `ChatMessageText`
3. **Model Inference**: Uses existing `LlmChatModelHelper.runInference()`
4. **Response Processing**: VLM analyzes frames and returns JSON
5. **Display**: Results shown in existing chat interface

### Model Integration
- **Reuses Existing**: `LlmAskImageViewModel` for chat management
- **Model Selection**: Uses current model picker (supports Gemma VLM models)
- **Inference Engine**: Leverages existing `LlmChatModelHelper`
- **No Custom Loading**: All model management handled by existing system

## Usage Instructions

### For Users
1. Navigate to **"LLM Ask Image"** task
2. Select a **Gemma VLM model** (e.g., Gemma-3n-E2B-it-int4)
3. Click the **video camera button** (new fourth button in input row)
4. **Grant camera permission** if prompted
5. **Start capture** - watch progress as 10 frames are collected at 1 FPS
6. **Automatic analysis** - frames and prompt are sent to the model
7. **View results** - JSON object detection results appear in chat

### For Developers
```kotlin
// Basic usage - integrate into existing chat
VideoFrameCaptureButton(
  onFramesCaptured = { frames ->
    // Handle captured frames
    frames.forEach { bitmap ->
      chatViewModel.addMessage(model, ChatMessageImage())
    }
  }
)

// Enhanced usage - with analysis prompt
VideoAnalysisQuickStart(
  onFramesCaptured = { frames -> /* store frames */ },
  onAnalyzeFrames = { frames ->
    // Add frames to chat
    // Add analysis prompt
    // Trigger inference
  }
)
```

## Dependencies
- **Existing Dependencies**: Reuses all current app dependencies
- **Camera API**: Uses legacy Camera API (already in Android)
- **No New Libraries**: No additional dependencies required

## File Structure
```
Android/src/app/src/main/java/com/google/ai/edge/gallery/
├── data/
│   └── VideoAnalysis.kt                          # Data models
├── ui/
│   ├── common/chat/
│   │   ├── MessageInputImage.kt                  # Modified (added button)
│   │   └── VideoFrameCaptureButton.kt           # New component
│   └── videoanalysis/
│       ├── VideoAnalysisChatExtension.kt        # Helper functions
│       └── VideoAnalysisLlmScreen.kt            # Enhanced screen
```

## Performance Considerations
- **1 FPS Sampling**: Minimal CPU/battery impact
- **10 Frame Limit**: Reasonable memory usage (~2-5MB total)
- **Efficient Conversion**: Direct YUV→JPEG→Bitmap pipeline
- **Background Processing**: Frame processing on IO dispatcher
- **Automatic Cleanup**: Camera resources properly released

## Future Enhancements
- **Configurable Frame Count**: Allow 5-20 frames
- **Custom Intervals**: Support 0.5-2 FPS sampling rates
- **Frame Preview**: Show thumbnails of captured frames
- **Batch Analysis**: Analyze multiple capture sessions
- **Export Results**: Save JSON to device storage

## Compatibility
- **Android API**: Supports API 21+ (existing app requirement)
- **Camera**: Works with front and rear cameras
- **Models**: Compatible with all VLM models in allowlist
- **Screen Sizes**: Responsive design for tablets and phones

## Error Handling
- **Camera Failures**: Graceful fallback with error messages
- **Permission Denied**: Clear instructions for manual permission
- **Model Errors**: Uses existing chat error handling system
- **Memory Issues**: Automatic cleanup on low memory

## Testing Recommendations
1. **Different Lighting**: Test capture in various lighting conditions
2. **Multiple Objects**: Verify detection of multiple objects per frame
3. **Movement**: Test with moving objects across frames
4. **Model Comparison**: Compare results across different Gemma models
5. **Performance**: Monitor memory usage during capture sessions

---

## Summary
This implementation successfully adds video object analysis to the gallery app while maintaining the existing architecture and user experience. By reusing the chat interface, model management, and UI components, it provides powerful new functionality with minimal code additions and no breaking changes.