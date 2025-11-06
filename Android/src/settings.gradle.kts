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

pluginManagement {
  repositories {
    // Try Google repository first
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }

    // Maven Central as primary alternative
    mavenCentral()

    // Gradle Plugin Portal
    gradlePluginPortal()

    // JitPack as additional source
    maven {
      url = uri("https://jitpack.io")
      content {
        includeGroupByRegex("com\\.github\\..*")
      }
    }
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "com.google.android.gms.oss-licenses-plugin") {
        useModule("com.google.android.gms:oss-licenses-plugin:0.10.6")
      }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // Enable local Maven cache for offline builds
    mavenLocal()

    // Primary repositories
    google()
    mavenCentral()

    // Additional fallback repositories
    maven {
      url = uri("https://jitpack.io")
      content {
        includeGroupByRegex("com\\.github\\..*")
      }
    }

    // JCenter as last resort (read-only)
    maven {
      url = uri("https://jcenter.bintray.com/")
      content {
        // Only use for specific libraries if needed
      }
    }
  }
}

rootProject.name = "Neural Forge"

include(":app")
