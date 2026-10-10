/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    // own id, so the app installs next to the Play Store version
    applicationId = "de.morgenschiss.gallery"
    minSdk = 31
    targetSdk = 37
    // CI sets 100000 + run number; local builds keep 46
    versionCode = System.getenv("GALLERY_VERSION_CODE")?.toIntOrNull() ?: 46
    versionName = "1.0.0"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    buildConfigField("String", "FEEDBACK_API_KEY", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // phones and the Apple-silicon emulator are arm64; the other ABIs would double the APK
    ndk { abiFilters += "arm64-v8a" }
  }

  signingConfigs {
    create("release") {
      // stable release key from CI secrets; only APKs signed with it can update the installed app
      val ks = System.getenv("GALLERY_KEYSTORE_FILE")
      if (ks != null) {
        storeFile = file(ks)
        storePassword = System.getenv("GALLERY_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("GALLERY_KEY_ALIAS")
        keyPassword = System.getenv("GALLERY_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      // R8 keeps the release APK well under Cloudflare's 100 MB upload limit
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // without the CI key (local builds) fall back to the debug key so the APK still installs
      signingConfig =
        if (System.getenv("GALLERY_KEYSTORE_FILE") != null) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  ksp("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  annotationProcessor("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(libs.tasks.vision)
  implementation(libs.tasks.retrieval)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.coil.compose)
}

configurations.all { resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0") }

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}

// oss-licenses 0.11.0 calls a Groovy-only method for debug builds under Gradle 9; release builds
// (the ones that get shipped) still collect the licenses
tasks.matching { it.name == "debugOssLicensesTask" }.configureEach { enabled = false }
