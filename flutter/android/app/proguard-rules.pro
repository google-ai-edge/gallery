# Suppress R8 warnings for optional Netty and Ktor dependencies
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration
-dontwarn io.netty.**
-dontwarn java.lang.management.**
-keep class io.netty.** { *; }

# Protobuf rules
-keep class com.google.protobuf.** { *; }
-keep class com.google.ai.edge.gallery.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Keep enum members for proto enums
-keepclassmembers enum com.google.ai.edge.gallery.proto.** { *; }

# Keep all internal app classes to prevent R8 from stripping fields used by Gson/Reflection
-keep class com.google.ai.edge.gallery.** { *; }
-keep class com.google.aiedge.edge_gallery_flutter.** { *; }

# Keep Hilt/Dagger classes
-keep class **_HiltModules* { *; }
-keep class **_ProvidesAdapter { *; }
-keep class **_Factory { *; }
