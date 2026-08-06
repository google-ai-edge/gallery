# AICore Sideloading — Reference

## Overview

Sideloading pushes a custom Gemma model to the device and has AICore serve it, bypassing the standard model download path. Useful for testing modified Gemma 4 with custom vision configurations or GemmaGaze integration.

## Prerequisites

- go/gemini-gchips-access (grants access to /cns/pj-d/home/tensor-gemini-gchips)
- AICore source at `java/com/google/android/apps/aicore/`
- Device connected via adb

## Steps

### 1. Push model to device

```bash
adb push /path/to/edgetpu_gem4_model/ /data/local/tmp/aicore/sideloaded_models/
```

### 2. Set up sideload directory

```bash
SIDE_LOAD_DIR=/data/user/0/com.google.android.aicore/files/sideloaded_models
FEATURES_DIR=/data/data/com.google.android.aicore/files/sideloaded_models

adb shell mkdir -p $SIDE_LOAD_DIR
adb shell mkdir -p $FEATURES_DIR
adb shell cp -r /data/local/tmp/aicore/sideloaded_models/edgetpu_gem4_model $SIDE_LOAD_DIR/
```

### 3. Push features.json

```bash
adb push features.json $FEATURES_DIR/features.json
```

#### Gemma 4 (Nano v4) with vision — features.json

```json
[
  {
    "feature_name": "Cortana Gemini Nano v4",
    "tpu": "edgetpu_gem4_v1.0_fully_obfuscated_p11_with_merged_models_3",
    "type": "CORTANA",
    "variant": "GOOGLE_EDGETPU",
    "parameters": {
      "prompt": "",
      "force_causal": true
    },
    "scheduling": {
      "request_kind": "REQUEST_KIND_INTERACTIVE"
    },
    "roles": {
      "text_input_role": "10167985913044593434",
      "text_output_role": "10167985913044593434",
      "image_input_role": "4870111188580693201"
    },
    "speculative_decode_config": {
      "gamma": 3,
      "type": "DRAFTER_MTP"
    }
  }
]
```

Key fields:
- `type: "CORTANA"` — vision-capable feature type (not "LLM")
- `image_input_role` — enables vision input
- `speculative_decode_config` — MTP drafting for faster decoding

### 4. Fix permissions

```bash
adb shell chmod -R a+rwx $FEATURES_DIR
```

Redo this every time you re-push model or config files.

### 5. Build and install AICore

```bash
# Use --debug=false for production-like behavior
./java/com/google/android/apps/aicore/build.sh --debug=false
adb install -d -r blaze-bin/java/com/google/android/apps/aicore/aicore-internal_arm64-v8a.apk
```

### 6. Restart AICore

```bash
adb shell am force-stop com.google.android.aicore
```

The sideloaded model appears as "Sideloaded: Cortana Gemini Nano v4" in the app.

## Troubleshooting

- **InferenceException: NOT_FOUND**: Re-run `chmod -R a+rwx` on the sideload dirs
- **Updated features.json not taking effect**: Restart AICore service, not just the demo app
- **Logs**: `adb logcat | grep "I native"` for AICore native performance logs

## References

- go/aicore-mdd-v2-sideload (latest sideloading instructions)
- `experimental/users/iparekh/video-understanding/sideload.sh` (teammate's script)
- Gemma 4 EdgeTPU models: go/gem-edgetpu-v4

## Relevance to GemmaGaze

If a modified Gemma 4 EdgeTPU model with GemmaGaze baked into the vision pipeline is produced, it can be sideloaded to test real spatial token dropping via AICore — without waiting for LiteRT-LM split position support. The model would handle the 70→8 soft token reduction internally.
