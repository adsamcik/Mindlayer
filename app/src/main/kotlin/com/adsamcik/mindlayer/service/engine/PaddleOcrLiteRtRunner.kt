package com.adsamcik.mindlayer.service.engine

/**
 * Narrow Kotlin-only seam over the three LiteRT models that form the
 * PP-OCRv5 mobile pipeline. JVM tests inject fakes here so they can exercise
 * preprocessing, DB post-processing, CTC decoding, and line assembly without
 * loading `liblitert.so`.
 */
internal interface PaddleOcrLiteRtRunner {
    fun runDetection(input: FloatArray): FloatArray
    fun runOrientation(input: FloatArray): FloatArray?
    fun runRecognition(input: FloatArray): FloatArray
    fun close()
}

/** Production factory signature: `(bundle, acceleratorLabel) -> runner`. */
internal typealias PaddleOcrLiteRtRunnerFactory = (PaddleOcrModelInfo, String) -> PaddleOcrLiteRtRunner
