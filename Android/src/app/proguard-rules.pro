# Native code and reflection: MediaPipe, LiteRT(-LM) and TFLite are called through JNI.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Gson parses the model allowlist and other app data by reflection.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.ai.edge.gallery.data.** { *; }

# Protobuf lite (DataStore settings).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Optional dependencies of transitive libraries that are not on the classpath.
-dontwarn com.google.mediapipe.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
