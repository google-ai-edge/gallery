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

import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

val localProperties = Properties()
rootProject.file("local.properties").let { file ->
  if (file.exists()) localProperties.load(FileInputStream(file))
}

android {
  namespace = "com.appswithlove.ai"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.appswithlove.ai"
    minSdk = 31
    targetSdk = 36
    versionCode = 2
    versionName = "1.1.0"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.appswithlove.ai.GalleryApplication"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "HF_TOKEN", "\"${localProperties.getProperty("hf.token", "")}\"")

    // Updraft SDK keys are injected via env vars (CI secrets). Empty when building locally,
    // which disables the in-app update check at runtime.
    buildConfigField("String", "UPDRAFT_APP_KEY", "\"${System.getenv("UPDRAFT_APP_KEY") ?: ""}\"")
    buildConfigField("String", "UPDRAFT_SDK_KEY", "\"${System.getenv("UPDRAFT_SDK_KEY") ?: ""}\"")
  }

  signingConfigs {
    val ksPath = System.getenv("KEYSTORE_PATH")
    if (!ksPath.isNullOrBlank()) {
      create("release") {
        storeFile = file(ksPath)
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: ""
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (!System.getenv("KEYSTORE_PATH").isNullOrBlank())
        signingConfigs.getByName("release")
      else
        signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
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
  implementation(libs.material3.expressive)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
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
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  implementation(libs.text.recognition)
  implementation(libs.graphics.shapes)
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.guava)
  implementation(libs.androidx.appfunctions)
  implementation(libs.androidx.appfunctions.service)
  implementation(libs.updraft.sdk)
  ksp(libs.androidx.appfunctions.compiler)
  ksp(libs.room.compiler)
  ksp(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}

ksp {
  arg("appfunctions:aggregateAppFunctions", "true")
  arg("appfunctions:generateMetadataFromSchema", "false")
}

// AppFunctions compiler generates `app_functions*.xml` into assets; ensure KSP runs before asset merge.
tasks.configureEach {
  if (!name.startsWith("merge") || !name.endsWith("Assets")) return@configureEach
  if (name.contains("ArtProfile")) return@configureEach
  val variant = name.removePrefix("merge").removeSuffix("Assets")
  val kspTask = "ksp${variant}Kotlin"
  if (tasks.names.contains(kspTask)) {
    dependsOn(kspTask)
  }
}
