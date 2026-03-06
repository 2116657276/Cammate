package com.liveaicapture.mvp.guide

import android.graphics.BitmapFactory
import com.liveaicapture.mvp.data.SceneType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight scene classifier for MVP.
 * Replace this class with YOLO/TFLite implementation later without touching UI contracts.
 */
class FrameSceneClassifier {

    fun classify(jpegBytes: ByteArray): SceneClassification {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return SceneClassification(SceneType.GENERAL, 0.35f)

        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val step = max(1, min(w, h) / 72)

        var total = 0
        var lumaSum = 0f
        var satSum = 0f
        var warmCount = 0
        var greenBlueCount = 0
        var centerSkinCount = 0
        var centerTotal = 0

        val cx1 = (w * 0.25f).toInt()
        val cx2 = (w * 0.75f).toInt()
        val cy1 = (h * 0.2f).toInt()
        val cy2 = (h * 0.8f).toInt()

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val maxRgb = max(max(r, g), b).toFloat()
                val minRgb = min(min(r, g), b).toFloat()
                val sat = if (maxRgb <= 1f) 0f else (maxRgb - minRgb) / maxRgb
                val luma = 0.299f * r + 0.587f * g + 0.114f * b

                total += 1
                lumaSum += luma
                satSum += sat

                if (r > g + 12 && r > b + 12) warmCount += 1
                if (g > r && b > r) greenBlueCount += 1

                val inCenter = x in cx1..cx2 && y in cy1..cy2
                if (inCenter) {
                    centerTotal += 1
                    if (isSkinLike(r, g, b)) centerSkinCount += 1
                }
                x += step
            }
            y += step
        }

        bitmap.recycle()

        if (total == 0) return SceneClassification(SceneType.GENERAL, 0.35f)

        val meanLuma = lumaSum / total
        val meanSat = satSum / total
        val warmRatio = warmCount.toFloat() / total.toFloat()
        val gbRatio = greenBlueCount.toFloat() / total.toFloat()
        val centerSkinRatio = if (centerTotal == 0) 0f else centerSkinCount.toFloat() / centerTotal.toFloat()

        if (meanLuma < 56f) {
            val conf = confidenceByMargin(56f - meanLuma, 48f)
            return SceneClassification(SceneType.NIGHT, conf)
        }

        if (centerSkinRatio > 0.10f && meanLuma in 60f..205f) {
            val conf = confidenceByMargin((centerSkinRatio - 0.10f) * 2f + (0.23f - abs(meanSat - 0.23f)), 0.6f)
            return SceneClassification(SceneType.PORTRAIT, conf)
        }

        if (warmRatio > 0.30f && meanSat > 0.25f) {
            val conf = confidenceByMargin((warmRatio - 0.30f) + (meanSat - 0.25f), 0.5f)
            return SceneClassification(SceneType.FOOD, conf)
        }

        if (gbRatio > 0.36f && meanLuma > 80f) {
            val conf = confidenceByMargin((gbRatio - 0.36f) + ((meanLuma - 80f) / 120f), 0.6f)
            return SceneClassification(SceneType.LANDSCAPE, conf)
        }

        return SceneClassification(SceneType.GENERAL, 0.52f)
    }

    private fun isSkinLike(r: Int, g: Int, b: Int): Boolean {
        val maxRgb = max(max(r, g), b)
        val minRgb = min(min(r, g), b)
        return r > 95 && g > 40 && b > 20 && (maxRgb - minRgb) > 15 && abs(r - g) > 15 && r > g && r > b
    }

    private fun confidenceByMargin(margin: Float, scale: Float): Float {
        val normalized = (margin / scale).coerceIn(0f, 1f)
        return (0.5f + normalized * 0.45f).coerceIn(0.5f, 0.95f)
    }
}

data class SceneClassification(
    val scene: SceneType,
    val confidence: Float,
)
