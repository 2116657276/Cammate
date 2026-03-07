package com.liveaicapture.mvp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.liveaicapture.mvp.data.OverlayState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CameraOverlay(state: OverlayState, rollDegrees: Float) {
    val animSpec = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)
    val targetX by animateFloatAsState(state.targetPointNorm?.x ?: -1f, animationSpec = animSpec, label = "targetX")
    val targetY by animateFloatAsState(state.targetPointNorm?.y ?: -1f, animationSpec = animSpec, label = "targetY")
    val subjectX by animateFloatAsState(state.subjectCenterNorm?.x ?: -1f, animationSpec = animSpec, label = "subjectX")
    val subjectY by animateFloatAsState(state.subjectCenterNorm?.y ?: -1f, animationSpec = animSpec, label = "subjectY")
    val boxL by animateFloatAsState(state.bboxNorm?.left ?: -1f, animationSpec = animSpec, label = "boxL")
    val boxT by animateFloatAsState(state.bboxNorm?.top ?: -1f, animationSpec = animSpec, label = "boxT")
    val boxR by animateFloatAsState(state.bboxNorm?.right ?: -1f, animationSpec = animSpec, label = "boxR")
    val boxB by animateFloatAsState(state.bboxNorm?.bottom ?: -1f, animationSpec = animSpec, label = "boxB")

    Canvas(modifier = Modifier.fillMaxSize()) {
        val oneThirdX = size.width / 3f
        val twoThirdX = size.width * 2f / 3f
        val oneThirdY = size.height / 3f
        val twoThirdY = size.height * 2f / 3f
        val thirdLineAlpha = if (state.grid == "thirds") 0.58f else 0.30f
        drawLine(Color.White.copy(alpha = thirdLineAlpha), Offset(oneThirdX, 0f), Offset(oneThirdX, size.height), strokeWidth = 2f)
        drawLine(Color.White.copy(alpha = thirdLineAlpha), Offset(twoThirdX, 0f), Offset(twoThirdX, size.height), strokeWidth = 2f)
        drawLine(Color.White.copy(alpha = thirdLineAlpha), Offset(0f, oneThirdY), Offset(size.width, oneThirdY), strokeWidth = 2f)
        drawLine(Color.White.copy(alpha = thirdLineAlpha), Offset(0f, twoThirdY), Offset(size.width, twoThirdY), strokeWidth = 2f)

        if (state.grid == "center") {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.55f), Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 2.4f)
            drawLine(Color.White.copy(alpha = 0.55f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.4f)
        }

        if (abs(rollDegrees) >= 6f) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val length = size.width * 0.44f
            val radians = (-rollDegrees / 180f * PI).toFloat()
            val dx = cos(radians) * length / 2f
            val dy = sin(radians) * length / 2f
            drawLine(
                color = Color(0xFFFFC857),
                start = Offset(center.x - dx, center.y - dy),
                end = Offset(center.x + dx, center.y + dy),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFFFFC857), radius = 7f, center = center)
        }

        if (targetX >= 0f && targetY >= 0f) {
            // PreviewView uses FILL_CENTER; map normalized points through the cropped frame space.
            val mappedPoint = mapNormPoint(
                normX = targetX.coerceIn(0f, 1f),
                normY = targetY.coerceIn(0f, 1f),
                canvasWidth = size.width,
                canvasHeight = size.height,
                sourceAspectRatio = state.sourceAspectRatio,
            )
            val px = mappedPoint.x
            val py = mappedPoint.y
            drawCircle(Color(0xFF22D3EE), radius = 14f, center = Offset(px, py), style = Stroke(width = 4f))
            drawLine(Color(0xFF22D3EE), Offset(px - 20f, py), Offset(px + 20f, py), strokeWidth = 3f, cap = StrokeCap.Round)
            drawLine(Color(0xFF22D3EE), Offset(px, py - 20f), Offset(px, py + 20f), strokeWidth = 3f, cap = StrokeCap.Round)
        }

        if (targetX >= 0f && targetY >= 0f && subjectX >= 0f && subjectY >= 0f) {
            val subjectPoint = mapNormPoint(
                normX = subjectX.coerceIn(0f, 1f),
                normY = subjectY.coerceIn(0f, 1f),
                canvasWidth = size.width,
                canvasHeight = size.height,
                sourceAspectRatio = state.sourceAspectRatio,
            )
            val targetPoint = mapNormPoint(
                normX = targetX.coerceIn(0f, 1f),
                normY = targetY.coerceIn(0f, 1f),
                canvasWidth = size.width,
                canvasHeight = size.height,
                sourceAspectRatio = state.sourceAspectRatio,
            )
            drawLine(
                color = Color(0xFFFFD166),
                start = subjectPoint,
                end = targetPoint,
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            val vx = targetPoint.x - subjectPoint.x
            val vy = targetPoint.y - subjectPoint.y
            val len = kotlin.math.sqrt(vx * vx + vy * vy)
            if (len > 12f) {
                val ux = vx / len
                val uy = vy / len
                val tip = targetPoint
                val wing = 18f
                val back = 26f
                val left = Offset(tip.x - ux * back - uy * wing, tip.y - uy * back + ux * wing)
                val right = Offset(tip.x - ux * back + uy * wing, tip.y - uy * back - ux * wing)
                drawLine(Color(0xFFFFD166), tip, left, strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(Color(0xFFFFD166), tip, right, strokeWidth = 4f, cap = StrokeCap.Round)
            }
            drawCircle(Color(0xFFFFD166), radius = 10f, center = subjectPoint, style = Stroke(width = 3f))
        }

        if (boxL >= 0f && boxT >= 0f && boxR >= 0f && boxB >= 0f) {
            val normRect = Rect(
                left = minOf(boxL, boxR).coerceIn(0f, 1f),
                top = minOf(boxT, boxB).coerceIn(0f, 1f),
                right = maxOf(boxL, boxR).coerceIn(0f, 1f),
                bottom = maxOf(boxT, boxB).coerceIn(0f, 1f),
            )
            val mapped = mapRect(
                normRect = normRect,
                canvasWidth = size.width,
                canvasHeight = size.height,
                sourceAspectRatio = state.sourceAspectRatio,
            )
            drawRect(
                color = Color(0xFFFFC857),
                topLeft = mapped.topLeft,
                size = mapped.size,
                style = Stroke(width = 4f),
            )
        }
    }
}

private fun mapRect(
    normRect: Rect,
    canvasWidth: Float,
    canvasHeight: Float,
    sourceAspectRatio: Float,
): Rect {
    val lt = mapNormPoint(
        normX = normRect.left.coerceIn(0f, 1f),
        normY = normRect.top.coerceIn(0f, 1f),
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        sourceAspectRatio = sourceAspectRatio,
    )
    val rb = mapNormPoint(
        normX = normRect.right.coerceIn(0f, 1f),
        normY = normRect.bottom.coerceIn(0f, 1f),
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        sourceAspectRatio = sourceAspectRatio,
    )
    return Rect(
        left = minOf(lt.x, rb.x),
        top = minOf(lt.y, rb.y),
        right = maxOf(lt.x, rb.x),
        bottom = maxOf(lt.y, rb.y),
    )
}

private fun mapNormPoint(
    normX: Float,
    normY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    sourceAspectRatio: Float,
): Offset {
    val srcRatio = sourceAspectRatio.coerceAtLeast(0.01f)
    val dstRatio = (canvasWidth / canvasHeight.coerceAtLeast(1f)).coerceAtLeast(0.01f)
    return if (srcRatio > dstRatio) {
        val scaledWidth = canvasHeight * srcRatio
        val cropX = (scaledWidth - canvasWidth) / 2f
        val x = normX * scaledWidth - cropX
        val y = normY * canvasHeight
        Offset(x, y)
    } else {
        val scaledHeight = canvasWidth / srcRatio
        val cropY = (scaledHeight - canvasHeight) / 2f
        val x = normX * canvasWidth
        val y = normY * scaledHeight - cropY
        Offset(x, y)
    }
}
