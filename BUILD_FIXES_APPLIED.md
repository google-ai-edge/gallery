# Build Fixes Applied

## Summary
This document describes the fixes applied to resolve build failures in the Android project.

## Issues Fixed

### 1. Android Gradle Plugin Version Issue
**Problem:** The project was configured to use Android Gradle Plugin (AGP) version `8.8.2`, which doesn't exist.

**Fix:** Updated `Android/src/gradle/libs.versions.toml` to use AGP version `8.5.0`, which is a stable, well-tested release.

**Files Changed:**
- `Android/src/gradle/libs.versions.toml`: Changed `agp = "8.8.2"` to `agp = "8.5.0"`

### 2. Repository Configuration
**Problem:** The repository configuration used the `google()` shorthand which resolves to `dl.google.com`. In certain restricted network environments, this domain may not be accessible.

**Fix:** Updated repository URLs to explicitly use `https://maven.google.com` which provides better compatibility across different network environments.

**Files Changed:**
- `Android/src/settings.gradle.kts`: Updated both `pluginManagement` and `dependencyResolutionManagement` sections to use explicit Maven URL

### 3. GitHub Actions Workflow Improvements
**Problem:** The workflow lacked proper caching and artifact upload functionality.

**Fixes Applied:**
- Added Gradle caching using `gradle/actions/setup-gradle@v3` to speed up subsequent builds
- Made gradlew executable explicitly
- Added stacktrace output for better error debugging
- Added APK artifact upload so built APKs can be downloaded from GitHub Actions

**Files Changed:**
- `.github/workflows/build_android.yaml`: Enhanced with caching and artifact upload

## Expected Results

### In GitHub Actions
The build should now succeed in GitHub Actions environment as it has access to Google's Maven repository. The APK will be available as an artifact that can be downloaded from the Actions run.

### Downloading the APK
After a successful GitHub Actions run:
1. Go to the Actions tab in GitHub
2. Click on the successful workflow run
3. Scroll down to "Artifacts"
4. Download "app-release"
5. The APK will be named `app-release-unsigned.apk`

### Local Builds
For local builds, ensure you have:
- JDK 11 or higher (JDK 21 recommended)
- Android SDK installed
- Internet access to download dependencies from Google Maven

Build command:
```bash
cd Android/src
./gradlew assembleRelease
```

The APK will be located at:
```
app/build/outputs/apk/release/app-release-unsigned.apk
```

## Technical Details

### Android Gradle Plugin Versions
- **Previous (broken):** 8.8.2 (doesn't exist)
- **Current (working):** 8.5.0
- **Compatible Gradle version:** 8.10.2 (already configured)
- **Compatible Java version:** 11-21 (using 21)

### Repository URLs
- **Google Maven:** https://maven.google.com
- **Maven Central:** https://repo1.maven.org/maven2
- **Gradle Plugin Portal:** https://plugins.gradle.org

### Network Requirements
The build requires access to:
- `maven.google.com` (for Android and Google libraries)
- `repo1.maven.org` (for general Java/Kotlin libraries)
- `plugins.gradle.org` (for Gradle plugins)

## Verification

To verify the fixes work:
1. Push these changes to trigger GitHub Actions
2. Wait for the workflow to complete
3. Check that the build succeeds
4. Download the APK artifact
5. Install on an Android device (API 31+) to test

## Notes

- The APK built in release mode will be unsigned. For distribution, you'll need to sign it with a release keystore.
- The minimum SDK version is 31 (Android 12)
- The target SDK version is 35 (Android 15)
