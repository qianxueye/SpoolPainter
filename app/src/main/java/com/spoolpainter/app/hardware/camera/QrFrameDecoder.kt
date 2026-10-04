package com.spoolpainter.app.hardware.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/** One instance per camera analysis executor; luminance only, no bitmap or image upload. */
class QrFrameDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    fun decode(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        require(width > 0 && height > 0 && pixelStride > 0 && rowStride >= (width - 1) * pixelStride + 1)
        val buffer = plane.duplicate()
        val start = buffer.position()
        require(start.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride < buffer.limit()) { "相机图像数据不完整" }
        val luminance = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) luminance[y * width + x] = buffer.get(start + y * rowStride + x * pixelStride)
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        for (candidate in listOf(source, source.invert())) {
            try { return reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text }
            catch (_: ReaderException) { /* No QR code in this frame. */ }
            finally { reader.reset() }
        }
        return null
    }
}
