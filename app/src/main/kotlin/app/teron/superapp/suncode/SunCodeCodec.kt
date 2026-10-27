package app.teron.superapp.suncode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.core.UserProfile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object SunCodeCodec {

    private const val OPTICAL_MAGIC = 0x5452 // 'TR'

    data class DecodedContact(
        val nodeId: String,
        val displayName: String,
        val handle: String,
        val publicKeyB64: String
    )

    fun encodeUri(profile: UserProfile): String {
        return Uri.Builder()
            .scheme("teron")
            .authority("contact")
            .appendQueryParameter("id", profile.nodeId)
            .appendQueryParameter("name", profile.displayName)
            .appendQueryParameter("handle", profile.handle)
            .appendQueryParameter("pk", profile.publicKeyB64)
            .build()
            .toString()
    }

    fun decodeUri(rawText: String): DecodedContact? {
        val trimmed = rawText.trim()
        val idx = trimmed.indexOf("teron://contact")
        val candidate = if (idx >= 0) trimmed.substring(idx).substringBefore(" ").substringBefore("\n") else trimmed
        if (!candidate.startsWith("teron://contact")) return null
        return try {
            val uri = Uri.parse(candidate)
            val id = uri.getQueryParameter("id")?.trim().orEmpty()
            if (!id.startsWith("TRN-")) return null
            val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { id }
            val handle = uri.getQueryParameter("handle")?.trim().orEmpty().ifBlank { "${id.lowercase(Locale.US)}@teron.mesh" }
            val pk = uri.getQueryParameter("pk")?.trim().orEmpty()
            DecodedContact(id, name, handle, pk)
        } catch (_: Exception) {
            null
        }
    }

    fun computeRayAmplitudes(nodeId: String, publicKeyB64: String): IntArray {
        val digest = MessageDigest.getInstance("SHA-512")
            .digest("$nodeId::$publicKeyB64".toByteArray(Charsets.UTF_8))
        val rays = IntArray(64)
        for (i in 0 until 64) {
            if (i % 16 == 0) {
                rays[i] = 4
            } else {
                rays[i] = ((digest[i].toInt() and 0xFF) % 4)
            }
        }
        return rays
    }

    /**
     * Encodes TRN-XXXXXXXX into a 64-bit optical word:
     * [16b magic 0x5452] [32b node hex] [16b CRC-16-CCITT]
     */
    fun packNodeOpticalWord(nodeId: String): Long? {
        val cleanHex = nodeId.trim().uppercase(Locale.US).removePrefix("TRN-")
        if (cleanHex.length != 8) return null
        val id32 = cleanHex.toLongOrNull(16) ?: return null
        val headerBytes = byteArrayOf(
            0x54,
            0x52,
            ((id32 ushr 24) and 0xFF).toByte(),
            ((id32 ushr 16) and 0xFF).toByte(),
            ((id32 ushr 8) and 0xFF).toByte(),
            (id32 and 0xFF).toByte()
        )
        val crc = crc16Ccitt(headerBytes)
        return (OPTICAL_MAGIC.toLong() shl 48) or ((id32 and 0xFFFFFFFFL) shl 16) or (crc.toLong() and 0xFFFFL)
    }

    fun unpackNodeOpticalWord(word: Long): String? {
        val magic = ((word ushr 48) and 0xFFFFL).toInt()
        if (magic != OPTICAL_MAGIC) return null
        val id32 = (word ushr 16) and 0xFFFFFFFFL
        val expectedCrc = (word and 0xFFFFL).toInt()
        val headerBytes = byteArrayOf(
            0x54,
            0x52,
            ((id32 ushr 24) and 0xFF).toByte(),
            ((id32 ushr 16) and 0xFF).toByte(),
            ((id32 ushr 8) and 0xFF).toByte(),
            (id32 and 0xFF).toByte()
        )
        if (crc16Ccitt(headerBytes) != expectedCrc) return null
        return String.format(Locale.US, "TRN-%08X", id32)
    }

    private fun crc16Ccitt(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Draws a crisp 10x10 optical fiducial badge inside a high-contrast white quiet zone
     * so live Camera2 viewfinders and JPEG-compressed messenger photos decode deterministically.
     */
    fun drawOpticalTag(canvas: Canvas, cx: Float, cy: Float, boxSize: Float, nodeId: String) {
        val word = packNodeOpticalWord(nodeId) ?: return
        val plateHalf = boxSize * 0.5f
        val plateRect = RectF(cx - plateHalf, cy - plateHalf, cx + plateHalf, cy + plateHalf)
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawRoundRect(plateRect, boxSize * 0.12f, boxSize * 0.12f, whitePaint)

        val darkPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFF0B0D11.toInt()
            isAntiAlias = false
        }

        val gridSpan = boxSize * 0.80f
        val cell = gridSpan / 10f
        val ox = cx - gridSpan / 2f
        val oy = cy - gridSpan / 2f

        for (r in 0 until 10) {
            for (c in 0 until 10) {
                val isDark = if (r == 0 || r == 9 || c == 0 || c == 9) {
                    // Top-left (0,0) is WHITE orientation anchor; all other 35 border cells are DARK
                    !(r == 0 && c == 0)
                } else {
                    val bitIdx = 63 - ((r - 1) * 8 + (c - 1))
                    ((word ushr bitIdx) and 1L) == 1L
                }
                if (isDark) {
                    val l = ox + c * cell
                    val t = oy + r * cell
                    canvas.drawRect(l, t, l + cell + 0.35f, t + cell + 0.35f, darkPaint)
                }
            }
        }
    }

    /**
     * Decodes a 10x10 Teron optical fiducial badge from a camera frame or gallery bitmap (PNG/JPEG).
     * Rotation-invariant across 0, 90, 180, 270 degrees; verified by 16-bit magic + 16-bit CRC-16.
     */
    fun decodeOpticalBitmap(source: Bitmap): String? {
        val maxDim = 320
        val scale = if (source.width > maxDim || source.height > maxDim) {
            maxDim.toFloat() / source.width.coerceAtLeast(source.height).toFloat()
        } else 1f
        val w = (source.width * scale).toInt().coerceAtLeast(32)
        val h = (source.height * scale).toInt().coerceAtLeast(32)
        val bmp = if (w != source.width || h != source.height) {
            Bitmap.createScaledBitmap(source, w, h, true)
        } else source

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        if (bmp !== source) bmp.recycle()

        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (r * 77 + g * 150 + b * 29) ushr 8
        }

        val minSide = min(w, h)
        // Candidate grid sizes (from ~18% to ~84% of frame min dimension)
        val sizes = mutableListOf<Int>()
        var s = (minSide * 0.18f).toInt().coerceAtLeast(24)
        val maxS = (minSide * 0.86f).toInt()
        while (s <= maxS) {
            sizes.add(s)
            s = (s * 1.13f).toInt().coerceAtLeast(s + 4)
        }

        val cellMeans = IntArray(100)

        for (span in sizes) {
            val step = (span / 11).coerceAtLeast(3)
            var y0 = 0
            while (y0 + span <= h) {
                var x0 = 0
                while (x0 + span <= w) {
                    val decoded = tryDecodeWindow(lum, w, x0, y0, span, cellMeans)
                    if (decoded != null) return decoded
                    x0 += step
                }
                y0 += step
            }
        }
        return null
    }

    private fun tryDecodeWindow(
        lum: IntArray,
        stride: Int,
        x0: Int,
        y0: Int,
        span: Int,
        cellMeans: IntArray
    ): String? {
        var minL = 255
        var maxL = 0
        for (r in 0 until 10) {
            val cyStart = y0 + (r * span) / 10 + span / 40
            val cyEnd = (y0 + ((r + 1) * span) / 10 - span / 40).coerceAtLeast(cyStart + 1)
            for (c in 0 until 10) {
                val cxStart = x0 + (c * span) / 10 + span / 40
                val cxEnd = (x0 + ((c + 1) * span) / 10 - span / 40).coerceAtLeast(cxStart + 1)
                var sum = 0
                var cnt = 0
                var yy = cyStart
                while (yy < cyEnd) {
                    val rowOff = yy * stride
                    var xx = cxStart
                    while (xx < cxEnd) {
                        sum += lum[rowOff + xx]
                        cnt++
                        xx++
                    }
                    yy++
                }
                val avg = if (cnt > 0) sum / cnt else 128
                cellMeans[r * 10 + c] = avg
                if (avg < minL) minL = avg
                if (avg > maxL) maxL = avg
            }
        }
        if (maxL - minL < 45) return null
        val thresh = (minL + maxL) / 2

        for (rot in 0..3) {
            fun cellAt(rr: Int, cc: Int): Int = when (rot) {
                0 -> cellMeans[rr * 10 + cc]
                1 -> cellMeans[(9 - cc) * 10 + rr]
                2 -> cellMeans[(9 - rr) * 10 + (9 - cc)]
                else -> cellMeans[cc * 10 + (9 - rr)]
            }

            // Corner (0,0) must be bright; other 3 corners must be dark
            if (cellAt(0, 0) <= thresh) continue
            if (cellAt(0, 9) >= thresh || cellAt(9, 0) >= thresh || cellAt(9, 9) >= thresh) continue

            var darkBorderCount = 0
            for (i in 0 until 10) {
                if (!(0 == 0 && i == 0) && cellAt(0, i) < thresh) darkBorderCount++
                if (cellAt(9, i) < thresh) darkBorderCount++
            }
            for (r in 1..8) {
                if (cellAt(r, 0) < thresh) darkBorderCount++
                if (cellAt(r, 9) < thresh) darkBorderCount++
            }
            if (darkBorderCount < 32) continue

            var word = 0L
            for (r in 1..8) {
                for (c in 1..8) {
                    word = (word shl 1) or (if (cellAt(r, c) < thresh) 1L else 0L)
                }
            }
            val nodeId = unpackNodeOpticalWord(word)
            if (nodeId != null) return nodeId
        }
        return null
    }

    /**
     * Renders a crisp architectural PNG card (Graphite Carbon + Cobalt Blue) containing BOTH
     * the radial SunCode and the scannable optical fiducial badge + lossless PNG tEXt chunk.
     */
    fun renderShareCardPng(profile: UserProfile): ByteArray {
        val w = 900
        val h = 1180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0D11.toInt() }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF151922.toInt() }
        val cardRect = RectF(44f, 44f, w - 44f, h - 44f)
        c.drawRoundRect(cardRect, 48f, 48f, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0xFF262D3D.toInt()
        }
        c.drawRoundRect(cardRect, 48f, 48f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3F5F8.toInt()
            textSize = 40f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("Teron • Карточка контакта", w / 2f, 128f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF949EAE.toInt()
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("Наведите сканер Teron или импортируйте изображение", w / 2f, 170f, subPaint)

        val cx = w / 2f
        val cy = 505f
        drawSunCodeGraphic(c, cx, cy, 265f, profile, phase = 0f)

        titlePaint.textSize = 44f
        c.drawText(profile.displayName, cx, 855f, titlePaint)

        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B82F6.toInt()
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        c.drawText("${profile.handle}  •  ${profile.nodeId}", cx, 905f, monoPaint)

        subPaint.textSize = 21f
        c.drawText("ECDH P-256: ${TeronCrypto.fingerprintHex(profile.publicKeyB64)}", cx, 952f, subPaint)

        // Bottom optical tag survives JPEG recompression in messengers!
        drawOpticalTag(c, cx, 1045f, 126f, profile.nodeId)

        val rawPngOut = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, rawPngOut)
        bmp.recycle()
        return injectPngTextChunk(rawPngOut.toByteArray(), "TeronSunCode", encodeUri(profile))
    }

    fun drawSunCodeGraphic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerRadius: Float,
        profile: UserProfile,
        phase: Float
    ) {
        val rays = computeRayAmplitudes(profile.nodeId, profile.publicKeyB64)
        val innerR = outerRadius * 0.56f
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (outerRadius * 0.028f).coerceAtLeast(3.5f)
        }

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Theme.withAlpha(Theme.border, 140)
        }
        canvas.drawCircle(cx, cy, innerR, guidePaint)

        for (i in 0 until 64) {
            val angle = (i * (360.0 / 64.0) - 90.0) * Math.PI / 180.0
            val amp = rays[i]
            val pulse = 1.0f + 0.025f * sin((phase + i * 0.2).toDouble()).toFloat()
            val lenFactor = when (amp) {
                4 -> 0.40f
                3 -> 0.32f
                2 -> 0.24f
                1 -> 0.16f
                else -> 0.09f
            } * pulse
            val rStart = innerR + outerRadius * 0.045f
            val rEnd = rStart + outerRadius * lenFactor

            rayPaint.color = when {
                amp == 4 -> Theme.brandText
                i % 2 == 0 -> Theme.brand
                else -> Theme.withAlpha(Theme.textSecondary, 185)
            }

            val x1 = cx + (rStart * cos(angle)).toFloat()
            val y1 = cy + (rStart * sin(angle)).toFloat()
            val x2 = cx + (rEnd * cos(angle)).toFloat()
            val y2 = cy + (rEnd * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, rayPaint)
        }

        // Scannable Center Optical Fiducial Badge inside Avatar Squircle
        val boxHalf = innerR * 0.76f
        val rect = RectF(cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf)
        val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.avatarColor
        }
        canvas.drawRoundRect(rect, boxHalf * 0.38f, boxHalf * 0.38f, sqPaint)

        val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (outerRadius * 0.012f).coerceAtLeast(2f)
            color = 0x33FFFFFF
        }
        canvas.drawRoundRect(rect, boxHalf * 0.38f, boxHalf * 0.38f, ringStroke)

        // Embed crisp 10x10 optical tag inside center squircle so pointing any Teron camera decodes instantly!
        drawOpticalTag(canvas, cx, cy, boxHalf * 1.48f, profile.nodeId)
    }

    private fun injectPngTextChunk(pngBytes: ByteArray, keyword: String, text: String): ByteArray {
        if (pngBytes.size < 33) return pngBytes
        val data = (keyword + "\u0000" + text).toByteArray(Charsets.UTF_8)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value.toInt()

        val iendOffset = pngBytes.size - 12
        val out = ByteArrayOutputStream(pngBytes.size + data.size + 12)
        out.write(pngBytes, 0, iendOffset)
        out.write(intToBytesBE(data.size))
        out.write(type)
        out.write(data)
        out.write(intToBytesBE(crc))
        out.write(pngBytes, iendOffset, 12)
        return out.toByteArray()
    }

    /**
     * Extracts contact from PNG tEXt chunk first; if image was JPEG-recompressed by Telegram/WhatsApp,
     * decodes the embedded 10x10 optical fiducial badge!
     */
    fun extractContactFromImageBytes(bytes: ByteArray): DecodedContact? {
        if (bytes.size < 24) return null
        val asText = String(bytes, Charsets.ISO_8859_1)
        val marker = "TeronSunCode\u0000"
        val pos = asText.indexOf(marker)
        if (pos >= 0) {
            val start = pos + marker.length
            val rest = ByteArray((bytes.size - start).coerceAtMost(2048))
            System.arraycopy(bytes, start, rest, 0, rest.size)
            val utf = String(rest, Charsets.UTF_8)
            val uriEnd = utf.indexOf("IEND").let { if (it > 4) it - 4 else utf.length }
            val decoded = decodeUri(utf.substring(0, uriEnd))
            if (decoded != null) return decoded
        }

        // Fallback: Optical bitmap decoding for JPEG/WebP recompressed screenshots & photos!
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val nodeId = decodeOpticalBitmap(bmp)
            bmp.recycle()
            if (nodeId != null) {
                DecodedContact(nodeId, nodeId, "${nodeId.lowercase(Locale.US)}@teron.mesh", "")
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun intToBytesBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )
}
