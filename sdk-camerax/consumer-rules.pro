# Consumers of :sdk-camerax should keep the public API and the
# Mindlayer OCR types referenced by OcrImageAnalyzer.
-keep class com.adsamcik.mindlayer.sdk.camerax.** { *; }
-keep class com.adsamcik.mindlayer.OcrFrameMeta { *; }
-keep class com.adsamcik.mindlayer.OcrFrameAck { *; }
