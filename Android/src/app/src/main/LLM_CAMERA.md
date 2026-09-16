# LlmCamera TPU Configuration

To build the LlmCamera feature with TPU-accelerated VoiceLM TTS, you must pass the `voicelm_tpu` definition during your `blaze` build. This builds the **experimental** flavor of the AI Edge Gallery app (`ai_edge_gallery_app_experimental`), which is required because the LlmCamera feature is currently behind the experimental build target.

**Example Build Command:**
```bash
blaze mobile-install --start_app -c opt --config=android_arm64-v8a --define=libunwind=true --define=xnnpack_use_latest_ops=true --define=keep_litertlm_symbols=true --define=voicelm_tpu=true //third_party/ai_edge_gallery/Android/src/app/src/main:ai_edge_gallery_app_experimental
```

**Important:** Before testing the TPU flavor on a device, you must enable unlisted apps to use the Edge TPU service. Run the following ADB command at least once on the test device:

```bash
adb shell setprop vendor.edgetpu.service.allow_unlisted_app true
```
