# ── Mindlayer app R8 / ProGuard rules ────────────────────────────────────────
# This file is applied on release builds (see isMinifyEnabled = true).
# Keep rules are tight on purpose; only surfaces reached via reflection, JNI,
# AIDL, or serializers are excluded from shrinking/obfuscation.

# Preserve line numbers and source file for readable crash reports on Play.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations — needed by Room, kotlinx.serialization, AndroidX.
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions

# ── AIDL ─────────────────────────────────────────────────────────────────────
# AIDL-generated Stub/Proxy classes are referenced across process boundaries
# and via reflection by Binder. Don't rename or strip them.
# F-045: narrowed from `com.adsamcik.mindlayer.service.**` (kept everything,
# including private internals) to: the manifest-declared service entry point
# plus any AIDL stub implementer. Other service-package classes can be
# shrunk/obfuscated normally — Parcelables, AIDL interfaces, Binder, and
# kotlinx.serialization rules below cover their reflective surfaces.
-keep class com.adsamcik.mindlayer.service.MindlayerMlService
-keep class * implements com.adsamcik.mindlayer.IMindlayerService { *; }
-keep class com.adsamcik.mindlayer.IMindlayerService { *; }
-keep class com.adsamcik.mindlayer.IMindlayerService$* { *; }
-keep class com.adsamcik.mindlayer.IStreamingCallback { *; }
-keep class com.adsamcik.mindlayer.IStreamingCallback$* { *; }
-keep interface com.adsamcik.mindlayer.** { *; }

# Any class extending Binder is touched reflectively by the platform.
-keep class * extends android.os.Binder { *; }

# ── Parcelables ──────────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable { *; }

# ── kotlinx.serialization ────────────────────────────────────────────────────
# Serializers are generated per @Serializable class and discovered via
# reflection (class.serializer() / Companion.serializer()).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keep,includedescriptorclasses class com.adsamcik.mindlayer.**$$serializer { *; }
-keepclassmembers class com.adsamcik.mindlayer.** {
    *** Companion;
}
-keepclasseswithmembers class com.adsamcik.mindlayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# ── Room ─────────────────────────────────────────────────────────────────────
# Room already ships consumer-rules.pro, but belt-and-braces for app-owned
# @Database/@Dao/@Entity types.
-keep class androidx.room.** { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ── SQLCipher ────────────────────────────────────────────────────────────────
# net.zetetic:sqlcipher-android uses JNI + reflection to bridge to libsqlcipher.so.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# ── LiteRT-LM native bindings ───────────────────────────────────────────────
# JNI entry points must keep their original names. We keep the whole package
# since the Kotlin wrapper re-exposes C++-backed types via reflection/JNI.
-keep class com.google.ai.edge.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.** {
    native <methods>;
}
-dontwarn com.google.ai.edge.**

# ── Kotlin coroutines ────────────────────────────────────────────────────────
# Standard coroutines keep rules (R8 ships defaults, but explicit is safer
# when using Flow from native-backed APIs).
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlinx.coroutines.flow.**

# ── Jetpack Compose / AndroidX ───────────────────────────────────────────────
# Compose has its own -keep rules baked in; this just silences edge warnings.
-dontwarn androidx.compose.**

# ── App entry points (manifest-declared) ────────────────────────────────────
# Manifest-registered components are kept automatically by R8, but we keep
# their init constructors explicit for clarity.
-keep class com.adsamcik.mindlayer.service.MindlayerMlService { <init>(); }
-keep class com.adsamcik.mindlayer.service.ui.MainActivity { <init>(); }

# ── Silence harmless warnings ────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
