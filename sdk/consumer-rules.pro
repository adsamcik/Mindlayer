# ── Mindlayer SDK consumer rules ─────────────────────────────────────────────
# These rules are applied to any app that depends on the Mindlayer SDK.
# They keep the public SDK surface reachable through R8 in downstream apps.

# Preserve annotations used by SDK serializers / Room DAOs.
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod

# Public SDK API — clients reference these directly, but include them for
# belt-and-braces when clients use reflection (e.g. DI frameworks).
-keep class com.adsamcik.mindlayer.sdk.** { public protected *; }
-keep interface com.adsamcik.mindlayer.sdk.** { *; }

# AIDL stubs the SDK uses to reach the service.
-keep class com.adsamcik.mindlayer.IMindlayerService { *; }
-keep class com.adsamcik.mindlayer.IMindlayerService$* { *; }
-keep class com.adsamcik.mindlayer.IStreamingCallback { *; }
-keep class com.adsamcik.mindlayer.IStreamingCallback$* { *; }

# Room entities / DAOs used by the conversation history DB inside client apps.
-keep @androidx.room.Database class com.adsamcik.mindlayer.sdk.db.** { *; }
-keep @androidx.room.Entity   class com.adsamcik.mindlayer.sdk.db.** { *; }
-keep @androidx.room.Dao      class com.adsamcik.mindlayer.sdk.db.** { *; }

# kotlinx.serialization generated serializers for SDK DTOs.
-keep,includedescriptorclasses class com.adsamcik.mindlayer.sdk.**$$serializer { *; }
-keepclassmembers class com.adsamcik.mindlayer.sdk.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher (client app also loads libsqlcipher.so to open the conversation DB).
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**
