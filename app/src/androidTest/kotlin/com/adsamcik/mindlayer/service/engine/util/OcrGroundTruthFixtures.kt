package com.adsamcik.mindlayer.service.engine.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

internal object OcrGroundTruthFixtures {
    data class GroundTruthBox(
        val text: String,
        val polygon: List<Pair<Float, Float>>,
    )

    data class Fixture(
        val name: String,
        val bitmap: Bitmap,
        val truth: List<GroundTruthBox>,
    )

    fun all(): List<Fixture> {
        val random = Random(FIXTURE_SEED)
        val fixtures = listOf(
            textFixture("single-word-24-left", 384, 192, Color.WHITE, 24f, listOf("MILK"), TextAlign.LEFT, 32f, 56f, random),
            textFixture("single-word-32-center", 384, 192, OFF_WHITE, 32f, listOf("BREAD"), TextAlign.CENTER, 32f, 64f, random),
            textFixture("single-word-48-left", 448, 224, LIGHT_GRAY, 48f, listOf("TOTAL"), TextAlign.LEFT, 36f, 72f, random),
            textFixture("single-word-64-center", 512, 256, Color.WHITE, 64f, listOf("INVOICE"), TextAlign.CENTER, 32f, 82f, random),
            textFixture("single-word-72-left", 640, 320, OFF_WHITE, 72f, listOf("AMOUNT"), TextAlign.LEFT, 44f, 104f, random),
            textFixture("single-line-24-left", 448, 224, Color.WHITE, 24f, listOf("ITEMA12"), TextAlign.LEFT, 36f, 72f, random),
            textFixture("single-line-32-center", 512, 256, LIGHT_GRAY, 32f, listOf("ORDER2026"), TextAlign.CENTER, 32f, 88f, random),
            textFixture("single-line-48-left", 640, 320, OFF_WHITE, 48f, listOf("REFABC123"), TextAlign.LEFT, 48f, 112f, random),
            textFixture("single-line-64-center", 640, 320, Color.WHITE, 64f, listOf("CODEX9"), TextAlign.CENTER, 32f, 110f, random),
            textFixture("two-line-24-left", 448, 256, OFF_WHITE, 24f, listOf("QTY12", "BOX4"), TextAlign.LEFT, 40f, 56f, random),
            textFixture("two-line-32-center", 512, 288, Color.WHITE, 32f, listOf("SUBTOTAL", "12345"), TextAlign.CENTER, 32f, 68f, random),
            textFixture("two-line-48-left", 640, 360, LIGHT_GRAY, 48f, listOf("ALPHA", "BETA42"), TextAlign.LEFT, 48f, 92f, random),
            textFixture("three-line-24-center", 512, 320, Color.WHITE, 24f, listOf("STORE", "AISLE7", "SKU1002"), TextAlign.CENTER, 32f, 54f, random),
            textFixture("three-line-32-left", 640, 360, OFF_WHITE, 32f, listOf("DATE20260523", "TAX123", "TOTAL987"), TextAlign.LEFT, 48f, 66f, random),
            textFixture("mixed-bold-48-center", 640, 320, Color.WHITE, 48f, listOf("BATCH314"), TextAlign.CENTER, 32f, 112f, random, Typeface.BOLD),
            textFixture("mixed-mono-32-left", 512, 256, LIGHT_GRAY, 32f, listOf("SN00042"), TextAlign.LEFT, 44f, 88f, random, Typeface.NORMAL, Typeface.MONOSPACE),
            emptyFixture("negative-white", 384, 192, Color.WHITE),
            emptyFixture("negative-off-white", 512, 256, OFF_WHITE),
            emptyFixture("negative-light-gray", 640, 320, LIGHT_GRAY),
            emptyFixture("negative-large-white", 640, 360, Color.WHITE),
        )
        check(fixtures.size == FIXTURE_COUNT) { "Expected $FIXTURE_COUNT OCR fixtures, got ${fixtures.size}" }
        return fixtures
    }

    private fun textFixture(
        name: String,
        width: Int,
        height: Int,
        background: Int,
        textSizePx: Float,
        lines: List<String>,
        align: TextAlign,
        leftMarginPx: Float,
        topPx: Float,
        random: Random,
        style: Int = Typeface.NORMAL,
        family: Typeface = Typeface.SANS_SERIF,
    ): Fixture {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx
            typeface = Typeface.create(family, style)
            isSubpixelText = false
        }
        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * LINE_HEIGHT_MULTIPLIER
        val jitterX = random.nextInt(-MAX_JITTER_PX, MAX_JITTER_PX + 1).toFloat()
        val jitterY = random.nextInt(-MAX_JITTER_PX, MAX_JITTER_PX + 1).toFloat()

        val truth = lines.mapIndexed { index, text ->
            val measuredWidth = paint.measureText(text)
            val left = when (align) {
                TextAlign.LEFT -> leftMarginPx + jitterX
                TextAlign.CENTER -> (width - measuredWidth) / 2f + jitterX
            }.coerceIn(0f, max(0f, width - measuredWidth))
            val top = topPx + index * lineHeight + jitterY
            val baseline = top - metrics.ascent
            canvas.drawText(text, left, baseline, paint)
            GroundTruthBox(
                text = text,
                polygon = textPolygon(left, baseline, measuredWidth, metrics, width, height),
            )
        }
        return Fixture(name, bitmap, truth)
    }

    private fun emptyFixture(name: String, width: Int, height: Int, background: Int): Fixture {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(background)
        return Fixture(name, bitmap, emptyList())
    }

    private fun textPolygon(
        left: Float,
        baseline: Float,
        measuredWidth: Float,
        metrics: Paint.FontMetrics,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): List<Pair<Float, Float>> {
        val top = (baseline + metrics.ascent).coerceIn(0f, bitmapHeight.toFloat())
        val right = (left + measuredWidth).coerceIn(0f, bitmapWidth.toFloat())
        val bottom = (baseline + metrics.descent).coerceIn(0f, bitmapHeight.toFloat())
        val clampedLeft = min(max(left, 0f), bitmapWidth.toFloat())
        return listOf(
            clampedLeft to top,
            right to top,
            right to bottom,
            clampedLeft to bottom,
        )
    }

    private enum class TextAlign { LEFT, CENTER }

    private const val FIXTURE_COUNT = 20
    private const val FIXTURE_SEED = 0x0C1206
    private const val MAX_JITTER_PX = 4
    private const val LINE_HEIGHT_MULTIPLIER = 1.18f
    private val OFF_WHITE = Color.rgb(250, 248, 242)
    private val LIGHT_GRAY = Color.rgb(238, 238, 238)
}
