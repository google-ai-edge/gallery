# ============================================================
# Gallery — ProGuard / R8 rules
# ============================================================

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin ---
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.**

# --- Kotlinx Serialization ---
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class * implements kotlinx.serialization.KSerializer { *; }

# --- App data/model classes (Gson reflection, kotlinx-serialization, Moshi) ---
-keep class com.appswithlove.ai.data.** { *; }
-keep class com.appswithlove.ai.common.** { *; }
-keep class com.appswithlove.ai.proto.** { *; }
-keep class com.appswithlove.ai.customtasks.**.data.** { *; }
-keep class com.appswithlove.ai.customtasks.insurancecard.** { *; }

# --- Gson ---
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes EnclosingMethod

# --- Moshi (Kotlin codegen) ---
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-dontwarn com.squareup.moshi.**

# --- LiteRT LM (on-device inference + function calling) ---
# @Tool/@ToolParam are scanned via reflection for function-calling.
-keep @interface com.google.ai.edge.litertlm.Tool
-keep @interface com.google.ai.edge.litertlm.ToolParam
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
    @com.google.ai.edge.litertlm.ToolParam <fields>;
    @com.google.ai.edge.litertlm.ToolParam <methods>;
    @com.google.ai.edge.litertlm.ToolParam <init>(...);
}
-keepclasseswithmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep all @Tool-bearing classes in custom tasks (function-calling tool sets)
-keep class com.appswithlove.ai.customtasks.tinygarden.TinyGardenTools { *; }
-keep class com.appswithlove.ai.customtasks.aijournal.AiJournalTools { *; }
-keep class com.appswithlove.ai.customtasks.agentchat.AgentTools { *; }

# --- Android App Functions ---
# KSP-generated bindings reflect on these; R8 renames would silently break voice invocation.
-keep class com.appswithlove.ai.customtasks.emoji.EmojiAppFunction { *; }
-keep class com.appswithlove.ai.customtasks.emoji.EmojiResult { *; }
-keep class com.appswithlove.ai.customtasks.insurancecard.InsuranceCardAppFunction { *; }
-keep class com.appswithlove.ai.customtasks.insurancecard.ScannerLaunchResult { *; }
-keepclassmembers class com.appswithlove.ai.customtasks.emoji.** {
    *** new(...);
    public static final ** Companion;
}
-keepclassmembers class com.appswithlove.ai.customtasks.insurancecard.** {
    *** new(...);
    public static final ** Companion;
}

# --- AppAuth (OAuth) ---
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# --- Updraft SDK (in-app update + feedback) ---
-keep class com.appswithlove.updraft.** { *; }
-dontwarn com.appswithlove.updraft.**

# --- WorkManager ---
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.impl.background.systemjob.SystemJobService
-keep class androidx.work.impl.background.systemalarm.SystemAlarmService

# --- Room ---
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# --- Protobuf Lite ---
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
-keepclassmembers class * extends com.google.protobuf.MessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- TensorFlow Lite (Play Services) ---
-keep class org.tensorflow.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn org.tensorflow.**

# --- Play Services OSS Licenses ---
-keep class com.google.android.gms.oss.licenses.** { *; }

# --- Hilt (generates Hilt_* classes, consumer proguard mostly covers) ---
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ViewComponentBuilderEntryPoint { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# --- AndroidX / Compose (consumer proguard mostly covers) ---
-dontwarn androidx.compose.**
-dontwarn androidx.**

# --- Retain JNI + native methods ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- R classes ---
-keep class **.R { *; }
-keep class **.R$* { *; }

# --- Enum values() / valueOf() ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelables ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
