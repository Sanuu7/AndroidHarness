# SPAKE2 registers these private native methods against a literal class name.
-keep class com.androidharness.app.data.env.adb.PairingContext { *; }

# JavaScript calls these methods by name rather than through JVM references.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager persists worker class names across app upgrades.
-keepnames class * extends androidx.work.ListenableWorker

# These names are shown in error fallbacks and in the agent's environment
# description, so obfuscating them would change user/model-visible text.
-keepattributes InnerClasses,EnclosingMethod
-keepnames class * extends java.lang.Throwable
-keep class com.androidharness.app.data.env.EnvState { *; }
-keep class com.androidharness.app.data.env.EnvState$* { *; }

# SSH algorithms and security providers instantiate implementations by name.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }

# Full protobuf messages use generated accessors through reflection.
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# These JSch integrations are optional desktop SSH-agent/Kerberos transports
# or logging adapters. Their dependencies are absent from the existing APK;
# Android SSH/SFTP uses the socket transport and the app's own logger.
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Commons Compress supports optional codecs supplied by separate artifacts.
# Neither codec is bundled in the existing APK. The compressor factory
# detects their availability before using the optional implementations.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
