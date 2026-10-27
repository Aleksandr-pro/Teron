package app.teron.superapp.suncode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.Theme
import app.teron.superapp.core.UserProfile
import app.teron.superapp.ui.Components
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.cos
import kotlin.math.sin

object SunCodeCodec {

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
            val handle = uri.getQueryParameter("handle")?.trim().orEmpty().ifBlank { "${id.lowercase()}@teron.mesh" }
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
                rays[i] = 4 // Cardinal calibration anchor
            } else {
                rays[i] = ((digest[i].toInt() and 0xFF) % 4)
            }
        }
        return rays
    }

    /**
     * Renders a crisp architectural PNG card (Graphite Carbon + Cobalt Blue) and embeds
     * a lossless PNG tEXt chunk ("TeronSunCode") containing the signed contact URI.
     */
    fun renderShareCardPng(profile: UserProfile): ByteArray {
        val w = 900
        val h = 1120
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
        c.drawText("Teron • Карточка контакта", w / 2f, 132f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF949EAE.toInt()
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("Импортируйте PNG-карточку или отсканируйте код в Teron", w / 2f, 176f, subPaint)

        val cx = w / 2f
        val cy = 530f
        drawSunCodeGraphic(c, cx, cy, 270f, profile, phase = 0f)

        titlePaint.textSize = 44f
        c.drawText(profile.displayName, cx, 895f, titlePaint)

        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF3B82F6.toInt()
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        c.drawText("${profile.handle}  •  ${profile.nodeId}", cx, 948f, monoPaint)

        subPaint.textSize = 21f
        c.drawText("ECDH P-256: ${TeronCrypto.fingerprintHex(profile.publicKeyB64)}", cx, 1002f, subPaint)

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

        // Clean geometric reference ring
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

        // Solid Architectural Center Squircle Monogram (zero neon gradient, zero emoji)
        val boxHalf = innerR * 0.74f
        val rect = RectF(cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf)
        val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = profile.avatarColor
        }
        canvas.drawRoundRect(rect, boxHalf * 0.42f, boxHalf * 0.42f, sqPaint)

        val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (outerRadius * 0.012f).coerceAtLeast(2f)
            color = 0x33FFFFFF
        }
        canvas.drawRoundRect(rect, boxHalf * 0.42f, boxHalf * 0.42f, ringStroke)

        val monogram = Theme.initialsFor(profile.displayName)
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = boxHalf * 0.74f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val fm = glyphPaint.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(monogram, cx, ty, glyphPaint)
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

    fun extractUriFromPngBytes(bytes: ByteArray): DecodedContact? {
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
            return decodeUri(utf.substring(0, uriEnd))
        }
        return null
    }

    private fun intToBytesBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )
}
