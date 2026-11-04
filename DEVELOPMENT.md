# Development notes

## Android app

The source tree currently contains the Android app under `Android/src`.

### Prerequisites

- Android Studio with its bundled JDK, or JDK 17 or newer for command-line builds.
- Android SDK Platform for the app's `compileSdk` version in
  [`Android/src/app/build.gradle.kts`](Android/src/app/build.gradle.kts).
- Android SDK Build-Tools installed through Android Studio's SDK Manager.
- Network access for the first Gradle sync/build so Gradle can download the wrapper distribution,
  Android Gradle Plugin, and project dependencies.

Android Studio normally configures the JDK and SDK paths for you. For command-line builds,
make sure `ANDROID_HOME` points to your Android SDK installation before running Gradle.

### Credential-free build check

From a fresh clone, verify that the project syncs and compiles before configuring any external
services:

```shell
cd Android/src
./gradlew :app:assembleDebug
```

This builds a debug APK with the checked-in placeholder Hugging Face OAuth values. It is useful as
a local build check, but model download sign-in will not work until you complete the Hugging Face
configuration below.

### Hugging Face OAuth configuration

To run the full application flow with model download support, configure your own Hugging Face
Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)).

After you've created a developer application:

1. In [`ProjectConfig.kt`](Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt),
   replace the placeholders for `clientId` and `redirectUri` with the values from your Hugging Face
   developer application.

1. In [`app/build.gradle.kts`](Android/src/app/build.gradle.kts), modify the
   `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured
   in your Hugging Face developer application.

### Run the app

Open `Android/src` in Android Studio, select an Android 12 or newer device or emulator, and run the
`app` configuration.

External boundary: a Hugging Face account and OAuth application are required for model download
sign-in. Device/emulator availability and model downloads are not covered by the Gradle build check.
