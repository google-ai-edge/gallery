plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.google.ai.edge.gallery"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    defaultConfig {
        applicationId = "com.google.aiedge.edge_gallery_flutter"
        minSdk = 31
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../../../Android/src/app/src/main/java")
            res.srcDirs("../../../Android/src/app/src/main/res")
            assets.srcDirs("../../../Android/src/app/src/main/assets")
            jniLibs.srcDirs("../../../Android/src/app/src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            pickFirsts += "META-INF/INDEX.LIST"
            pickFirsts += "META-INF/io.netty.versions.properties"
            pickFirsts += "META-INF/okio.kotlin_module"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore:1.1.7")
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
    implementation("com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha02")
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-support:16.4.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.core:core-splashscreen:1.2.0-beta01")
    implementation("com.google.protobuf:protobuf-javalite:4.26.1")
    implementation("com.google.dagger:hilt-android:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-netty:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    kapt("com.google.dagger:hilt-android-compiler:2.57.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.26.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
