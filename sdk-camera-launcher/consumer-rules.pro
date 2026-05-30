# Keep the parcelable wire types — they're read across Activity Result
# bundles in the consumer's process. Members are required for the
# Parcelize-generated CREATOR fields.
-keep class com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureRequest { *; }
-keep class com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureResult { *; }
-keep class com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureResult$* { *; }
-keep class com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureMode { *; }

# Keep the activity entry-point so the consumer's manifest merger
# resolves it after R8.
-keep class com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureActivity { *; }
