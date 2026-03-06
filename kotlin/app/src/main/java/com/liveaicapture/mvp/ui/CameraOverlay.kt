package com.liveaicapture.mvp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.liveaicapture.mvp.data.OverlayState

@Composable
fun CameraOverlay(state: OverlayState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (state.grid == "thirds") {
            val oneThirdX = size.width / 3f
            val twoThirdX = size.width * 2f / 3f
            val oneThirdY = size.height / 3f
            val twoThirdY = size.height * 2f / 3f

            drawLine(Color.White.copy(alpha = 0.5f), Offset(oneThirdX, 0f), Offset(oneThirdX, size.height), strokeWidth = 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(twoThirdX, 0f), Offset(twoThirdX, size.height), strokeWidth = 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, oneThirdY), Offset(size.width, oneThirdY), strokeWidth = 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, twoThirdY), Offset(size.width, twoThirdY), strokeWidth = 2f)
        } else if (state.grid == "center") {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.5f), Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2f)
        }

        state.targetPointNorm?.let { normPoint ->
            // PreviewView uses FILL_CENTER; map normalized points through the cropped frame space.
            val mappedPoint = mapNormPoint(
                normX = normPoint.x.coerceIn(0f, 1f),
                normY = normPoint.y.coerceIn(0f, 1f),
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

        state.bboxNorm?.let { normRect ->
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
