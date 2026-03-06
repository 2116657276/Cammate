package com.liveaicapture.mvp.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

object FrameEncoder {

    fun imageProxyToBase64(imageProxy: ImageProxy): String {
        val jpegBytes = imageProxyToJpegBytes(imageProxy)
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    }

    fun imageProxyToJpegBytes(imageProxy: ImageProxy): ByteArray {
        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null,
        )

        val yuvToJpeg = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, yuvToJpeg)
        val rawBitmap = BitmapFactory.decodeByteArray(yuvToJpeg.toByteArray(), 0, yuvToJpeg.size())
            ?: throw IllegalStateException("Failed to decode JPEG from YUV")

        val rotatedBitmap = rotateBitmap(rawBitmap, imageProxy.imageInfo.rotationDegrees)
        val resizedBitmap = resizeLongEdge(rotatedBitmap, 1024)

        val finalJpeg = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, finalJpeg)
        return finalJpeg.toByteArray()
    }

    // CameraX analysis provides YUV_420_888 planes; this converts it to NV21 for JPEG encoding.
    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        var position = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, position, width)
            position += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        for (row in 0 until height / 2) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until width / 2) {
                nv21[position++] = vBuffer.get(vRowStart + col * vPixelStride)
                nv21[position++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }
        return nv21
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return bitmap

        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight, true)
    }
}
