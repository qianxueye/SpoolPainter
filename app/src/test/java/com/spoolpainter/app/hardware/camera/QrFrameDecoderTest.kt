package com.spoolpainter.app.hardware.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class QrFrameDecoderTest {
    @Test fun `padded strided camera luminance decodes standard spool QR in either polarity`() {
        val payload = "WEB+SPOOLMAN:S-123"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 224, 224)
        val width = matrix.width
        val height = matrix.height
        val rowStride = width * 2 + 12
        for (invert in listOf(false, true)) {
            val buffer = ByteBuffer.allocate(7 + rowStride * height)
            buffer.position(7)
            for (y in 0 until height) for (x in 0 until width) {
                val black = matrix[x, y] != invert
                buffer.put(7 + y * rowStride + x * 2, if (black) 0.toByte() else 255.toByte())
            }
            assertEquals(payload, QrFrameDecoder().decode(buffer, width, height, rowStride, 2))
            assertEquals(7, buffer.position())
        }
    }
    @Test fun `rotated server URL QR decodes without assuming device orientation`() {
        val payload = "http://inventory.example:7912/spool/show/456"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
        val size = matrix.width
        val buffer = ByteBuffer.allocate(size * size)
        for (y in 0 until size) for (x in 0 until size) buffer.put(y * size + x, if (matrix[y, size - 1 - x]) 0.toByte() else 255.toByte())
        assertEquals(payload, QrFrameDecoder().decode(buffer, size, size, size, 1))
    }
    @Test fun `small dim paper QR in a larger camera frame decodes`() {
        val payload = "WEB+SPOOLMAN:S-28"
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 210, 210)
        val width = 1280
        val height = 720
        val buffer = ByteBuffer.allocate(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val qx = x - 535
            val qy = y - 255
            val black = qx in 0 until qr.width && qy in 0 until qr.height && qr[qx, qy]
            val ambient = 85 + x * 40 / width
            buffer.put(y * width + x, (if (black) ambient - 65 else ambient).toByte())
        }
        assertEquals(payload, QrFrameDecoder().decode(buffer, width, height, width, 1))
    }
    @Test fun `blank frame and truncated plane cannot produce a spurious selection`() {
        assertNull(QrFrameDecoder().decode(ByteBuffer.wrap(ByteArray(100 * 100) { 255.toByte() }), 100, 100, 100, 1))
        assertThrows(IllegalArgumentException::class.java) { QrFrameDecoder().decode(ByteBuffer.allocate(10), 100, 100, 100, 1) }
    }
}
