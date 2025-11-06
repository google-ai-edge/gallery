# 🔨 Neural Forge Build Guide

Complete guide for building Neural Forge, including solutions for network/connectivity issues.

## Table of Contents
- [Prerequisites](#prerequisites)
- [Quick Build](#quick-build)
- [Offline Build Setup](#offline-build-setup)
- [Troubleshooting](#troubleshooting)
- [Build Variants](#build-variants)
- [CI/CD Build](#cicd-build)

---

## Prerequisites

### System Requirements
- **OS**: Windows, macOS, or Linux
- **RAM**: 8GB minimum (4GB for Gradle, 4GB for system)
- **Storage**: 10GB free space
- **Java**: JDK 11 or higher

### Software Requirements
- **Android SDK**: API 31+ (Android 12+)
- **Gradle**: 8.10.2 (bundled via wrapper)
- **Optional**: Android Studio Arctic Fox or newer

### Check Your Setup
```bash
# Check Java version
java -version
# Should show Java 11 or higher

# Check available memory
free -h  # Linux
# OR
wmic OS get TotalVisibleMemorySize,FreePhysicalMemory  # Windows
```

---

## Quick Build

### Standard Build (requires internet)

```bash
# Navigate to project
cd edge-gallery/Android/src

# Clean previous builds
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

### Install on Device

```bash
# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# OR install and launch
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.neuralforge.mobile/.MainActivity
```

---

## Offline Build Setup

If you have **limited or no internet access**, follow these steps:

### Step 1: One-Time Dependency Download

On a machine **with internet**, run this once to download all dependencies:

```bash
cd edge-gallery/Android/src

# Download all dependencies and cache them
./gradlew build --refresh-dependencies

# This will:
# 1. Download Gradle wrapper (if needed)
# 2. Download all Android dependencies
# 3. Download all Kotlin/Java dependencies
# 4. Cache everything in ~/.gradle/caches/
```

### Step 2: Export Gradle Cache

After successful download, export the cache:

```bash
# Create archive of Gradle cache
cd ~/.gradle
tar -czf gradle-cache.tar.gz caches/ wrapper/

# Move to project for easy transfer
mv gradle-cache.tar.gz ~/edge-gallery/
```

### Step 3: Transfer to Offline Machine

Transfer these to your offline machine:
1. `edge-gallery/` (your project)
2. `gradle-cache.tar.gz` (dependencies)

### Step 4: Setup on Offline Machine

```bash
# Extract Gradle cache
cd ~
mkdir -p .gradle
cd .gradle
tar -xzf /path/to/gradle-cache.tar.gz

# Now build offline
cd /path/to/edge-gallery/Android/src
./gradlew assembleDebug --offline
```

---

## Troubleshooting

### Problem: "Could not resolve all dependencies"

**Cause**: Can't connect to Google/Maven repositories

**Solutions**:

1. **Use Offline Mode** (if you've built before):
   ```bash
   ./gradlew assembleDebug --offline
   ```

2. **Increase Timeout** (slow connection):
   ```bash
   # Add to gradle.properties (already configured)
   systemProp.http.connectionTimeout=120000
   systemProp.http.socketTimeout=120000
   ```

3. **Use Local Maven** (if dependencies are cached):
   ```bash
   # Already enabled in settings.gradle.kts
   mavenLocal()
   ```

4. **Retry with Refresh**:
   ```bash
   ./gradlew clean build --refresh-dependencies --stacktrace
   ```

### Problem: "Execution failed for task ':app:processDebugGoogleServices'"

**Cause**: Firebase/Google Services configuration missing

**Solution**: This is expected and won't crash the app. Firebase is optional.

If you want to disable it completely:

```bash
# Edit app/build.gradle.kts
# Change this line:
alias(libs.plugins.google.services) apply false

# To:
# alias(libs.plugins.google.services) apply false  // Already disabled
```

### Problem: "Out of memory" during build

**Cause**: Insufficient RAM allocated to Gradle

**Solutions**:

1. **Increase Gradle Memory** (already set to 2GB):
   ```bash
   # In gradle.properties (already configured):
   org.gradle.jvmargs=-Xmx2048m

   # If you have more RAM, increase to 4GB:
   org.gradle.jvmargs=-Xmx4096m
   ```

2. **Disable Parallel Builds** temporarily:
   ```bash
   # In gradle.properties, comment out:
   # org.gradle.parallel=true
   ```

3. **Build with Limited Workers**:
   ```bash
   ./gradlew assembleDebug --max-workers=2
   ```

### Problem: "Failed to download Gradle distribution"

**Cause**: Can't reach services.gradle.org

**Solutions**:

1. **Manual Gradle Installation**:
   ```bash
   # Download gradle-8.10.2-bin.zip from another machine
   # Place in: ~/.gradle/wrapper/dists/gradle-8.10.2-bin/
   # Then unzip and build
   ```

2. **Use System Gradle** (if installed):
   ```bash
   gradle assembleDebug
   # Instead of ./gradlew
   ```

### Problem: "SDK location not found"

**Cause**: Android SDK path not set

**Solution**:

Create `local.properties` in `Android/src/`:
```properties
sdk.dir=/path/to/Android/Sdk

# Common paths:
# Linux: /home/username/Android/Sdk
# macOS: /Users/username/Library/Android/sdk
# Windows: C\:\\Users\\username\\AppData\\Local\\Android\\Sdk
```

### Problem: Build takes forever

**Solutions**:

1. **Enable Build Cache** (already configured):
   ```bash
   # In gradle.properties:
   org.gradle.caching=true
   ```

2. **Use Incremental Builds**:
   ```bash
   # Don't use 'clean' unless necessary
   ./gradlew assembleDebug
   # Instead of:
   # ./gradlew clean assembleDebug
   ```

3. **Skip Tests**:
   ```bash
   ./gradlew assembleDebug -x test -x lint
   ```

---

## Build Variants

### Debug Build (Faster, for Development)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Features:
- Debuggable
- Not minified
- Larger APK size (~30-50MB)
- Faster build time

### Release Build (Optimized, for Distribution)

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

Features:
- Not debuggable
- Minified with R8
- Smaller APK size (~15-25MB)
- Slower build time
- Requires signing for installation

---

## CI/CD Build

For GitHub Actions, Jenkins, or other CI/CD:

### GitHub Actions Example

```yaml
name: Build Neural Forge

on:
  push:
    branches: [ main, claude/* ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'

    - name: Cache Gradle packages
      uses: actions/cache@v3
      with:
        path: |
          ~/.gradle/caches
          ~/.gradle/wrapper
        key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
        restore-keys: |
          ${{ runner.os }}-gradle-

    - name: Grant execute permission for gradlew
      run: chmod +x Android/src/gradlew

    - name: Build with Gradle
      run: |
        cd Android/src
        ./gradlew assembleDebug --stacktrace

    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-debug
        path: Android/src/app/build/outputs/apk/debug/app-debug.apk
```

### Docker Build

```dockerfile
FROM openjdk:11-jdk

# Install Android SDK
RUN apt-get update && apt-get install -y wget unzip

# Download Android command line tools
RUN wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O /tmp/tools.zip && \
    mkdir -p /opt/android-sdk/cmdline-tools && \
    unzip /tmp/tools.zip -d /opt/android-sdk/cmdline-tools && \
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Accept licenses
RUN yes | sdkmanager --licenses

# Install required SDK components
RUN sdkmanager "platform-tools" "platforms;android-31" "build-tools;33.0.0"

# Copy project
COPY . /app
WORKDIR /app/Android/src

# Build
RUN ./gradlew assembleDebug --no-daemon --stacktrace
```

---

## Build Performance Tips

### 1. Use Build Cache
Already enabled in `gradle.properties`:
```properties
org.gradle.caching=true
```

### 2. Enable Parallel Execution
Already enabled:
```properties
org.gradle.parallel=true
```

### 3. Skip Unnecessary Tasks
```bash
# Skip tests and linting
./gradlew assembleDebug -x test -x lint -x lintVitalRelease
```

### 4. Use Configuration Cache
Already enabled:
```properties
org.gradle.configuration-cache=true
```

### 5. Incremental Builds
```bash
# Don't clean unless necessary
./gradlew assembleDebug
# First build: ~3-5 minutes
# Incremental builds: ~30-60 seconds
```

---

## Verification Checklist

After successful build:

- [ ] APK exists at `app/build/outputs/apk/debug/app-debug.apk`
- [ ] APK size is reasonable (~30-50MB for debug)
- [ ] APK installs on device without errors
- [ ] App launches and shows "Neural Forge" branding
- [ ] No immediate crashes

### Verify APK

```bash
# Check APK info
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "package|application-label"

# Should show:
# package: name='com.neuralforge.mobile'
# application-label:'Neural Forge'
```

---

## Getting Help

If you encounter issues not covered here:

1. **Check build logs**:
   ```bash
   ./gradlew assembleDebug --stacktrace --info > build.log 2>&1
   ```

2. **Clean and retry**:
   ```bash
   ./gradlew clean
   rm -rf ~/.gradle/caches/
   ./gradlew assembleDebug --refresh-dependencies
   ```

3. **Check system resources**:
   ```bash
   # Ensure enough disk space
   df -h

   # Check memory
   free -h
   ```

4. **Verify Java/Android setup**:
   ```bash
   java -version
   echo $ANDROID_SDK_ROOT
   ```

---

## Summary

### For Normal Builds
```bash
cd edge-gallery/Android/src
./gradlew assembleDebug
```

### For Offline Builds
```bash
# First time (with internet):
./gradlew build --refresh-dependencies

# Then offline:
./gradlew assembleDebug --offline
```

### For CI/CD
```bash
./gradlew assembleDebug --no-daemon --stacktrace
```

---

**Neural Forge is optimized for reliable builds even with limited connectivity!** 🔥

The build system now includes:
- ✅ Local Maven cache support
- ✅ Extended timeouts for slow connections
- ✅ Offline build capability
- ✅ Firebase made optional
- ✅ Multiple repository mirrors
- ✅ Optimized caching

**Ready to build!** 🚀
