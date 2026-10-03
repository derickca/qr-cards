package ca.derickcampbell.qrcards.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders QR codes from payload strings.
 *
 * Error-correction guidance (Reed-Solomon, share of recoverable codewords):
 * - [ErrorCorrectionLevel.M] (~15%) is the sane default for on-screen display,
 *   where nothing degrades the image.
 * - [ErrorCorrectionLevel.H] (~30%) for anything printed or exported, where
 *   wear, dirt, bad lighting, or a center logo can damage modules.
 *
 * All personal scenarios in this app (contacts, Wi-Fi, locations) are tens to
 * hundreds of characters — they encode at small versions and scan effortlessly.
 */
object QrRenderer {

    /**
     * Renders [payload] to a square [sizePx] bitmap (black on white).
     * Throws [IllegalArgumentException] via ZXing if the payload is too large.
     */
    fun renderBitmap(
        payload: String,
        sizePx: Int,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Bitmap {
        val matrix = encode(payload, ecLevel)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
            .let { raw ->
                if (raw.width == sizePx) raw
                else Bitmap.createScaledBitmap(raw, sizePx, sizePx, false)
            }
    }

    /**
     * Renders [payload] to an SVG string (vector — infinitely scalable, ideal
     * for marketing materials and print shops). One `<rect>` per dark module
     * grouped under a single path-free group; white background rect first.
     */
    fun renderSvg(
        payload: String,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
        modulePx: Int = 10,
    ): String {
        val matrix = encode(payload, ecLevel)
        val w = matrix.width
        val h = matrix.height
        val size = w * modulePx
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$size" height="$size" viewBox="0 0 $size $size">""")
        sb.append("""<rect width="$size" height="$size" fill="white"/>""")
        sb.append("""<g fill="black">""")
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (matrix.get(x, y)) {
                    sb.append("""<rect x="${x * modulePx}" y="${y * modulePx}" width="$modulePx" height="$modulePx"/>""")
                }
            }
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    private fun encode(payload: String, ecLevel: ErrorCorrectionLevel) =
        QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            /* width = */ 0,
            /* height = */ 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ecLevel,
                // Quiet zone: ZXing default margin is 4 modules; 2 is plenty
                // for on-screen codes and keeps them compact.
                EncodeHintType.MARGIN to 2,
            ),
        )
}
