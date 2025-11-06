pluginManagement {
    repositories {
        // Add Aliyun mirrors FIRST (faster in most regions)
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin/")
            content {
                includeGroupByRegex(".*")
            }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/google/")
            content {
                includeGroupByRegex(".*")
            }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public/")
            content {
                includeGroupByRegex(".*")
            }
        }
        // Keep original repositories as fallbacks
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Add Aliyun mirrors FIRST
        maven {
            url = uri("https://maven.aliyun.com/repository/google/")
            content {
                includeGroupByRegex(".*")
            }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public/")
            content {
                includeGroupByRegex(".*")
            }
        }
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex(".*")
            }
        }
        // Keep original repositories as fallbacks
        google()
        mavenCentral()
    }
}

rootProject.name = "Neural Forge"
include(":app")
