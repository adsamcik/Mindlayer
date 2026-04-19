# ── Mindlayer :shared consumer rules ─────────────────────────────────────────
# Parcelable types and AIDL interfaces cross process boundaries and are
# touched reflectively by Binder / kotlinx.serialization. Keep them all.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Parcelables (kotlin-parcelize + AIDL).
-keep class com.adsamcik.mindlayer.** implements android.os.Parcelable { *; }
-keepclassmembers class com.adsamcik.mindlayer.** implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Public shared types referenced by the AIDL surface.
-keep class com.adsamcik.mindlayer.Types** { *; }
-keep class com.adsamcik.mindlayer.shared.** { *; }

# kotlinx.serialization generated serializers.
-keep,includedescriptorclasses class com.adsamcik.mindlayer.**$$serializer { *; }
-keepclassmembers class com.adsamcik.mindlayer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
