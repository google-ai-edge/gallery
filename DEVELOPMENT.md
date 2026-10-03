# Development notes

## Build app locally

To successfully build and run the application through Android Studio, you need to configure it with your own HuggingFace Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)). This is required for the model download functionality to work correctly.

After you've created a developer application:

1. In [`ProjectConfig.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the placeholders for `clientId` and `redirectUri` with the values from your HuggingFace developer application.

1. In [`app/build.gradle.kts`](https://github.com/google-ai-edge/gallery/blob/c1b50e160a66d5ea2ec2d8d8e63088b3cc0761bc/Android/src/app/build.gradle.kts#L41-L44), modify the `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured in your HuggingFace developer application.

## Build Windows Desktop App

To build and run the Windows desktop application:

1. Ensure OpenJDK 21 or higher is installed and set on your `PATH`.
2. Navigate to the `Android/src` directory:
   ```bash
   cd Android/src
   ```
3. Run the desktop application directly:
   ```bash
   ./gradlew :desktop:run
   ```
4. Or package a standalone Windows installer (`.msi`):
   ```bash
   ./gradlew :desktop:packageMsi
   ```
   The installer will be placed in `desktop/build/compose/binaries/main/msi/`.

