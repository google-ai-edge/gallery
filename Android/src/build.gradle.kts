// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.application) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.oss.licenses) apply false
    // Google Services removed - optional Firebase feature
}
