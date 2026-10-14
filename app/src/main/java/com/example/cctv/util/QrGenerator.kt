package com.example.cctv.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * QR Code generator using ZXing library.
 * Produces real, scannable QR codes from any text input.
 */
object QrGenerator {

    private const val TAG = "QrGenerator"

    /**
     * Generate a real, scannable QR code bitmap from text.
     *
     * @param text The data to encode (e.g. pairing URL)
     * @param size The width/height of the output bitmap in pixels
     * @return A black-on-white QR code Bitmap
     */
    fun generate(text: String, size: Int = 512): Bitmap {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed for text: $text", e)
            // Fallback: return a simple placeholder bitmap
            createFallbackBitmap(size)
        }
    }

    private fun createFallbackBitmap(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        // Draw a simple "X" to indicate error
        for (i in 0 until size) {
            val margin = size / 8
            if (i in margin until size - margin) {
                bitmap.setPixel(i, i, Color.RED)
                bitmap.setPixel(i, size - 1 - i, Color.RED)
            }
        }
        return bitmap
    }
}
