package app.teron.superapp.suncode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import app.teron.superapp.core.Theme
import app.teron.superapp.core.UserProfile
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

    /**
     * Generates 64 deterministic radial ray heights (0..3) with 4 cardinal anchor rays.
     */
    fun computeRayAmplitudes(nodeId: String, publicKeyB64: String): IntArray {
        val digest = MessageDigest.getInstance("SHA-512")
            .digest("$nodeId::$publicKeyB64".toByteArray(Charsets.UTF_8))
        val rays = IntArray(64)
        for (i in 0 until 64) {
            if (i % 16 == 0) {
                rays[i] = 4 // Cardinal sync ray
            } else {
                rays[i] = ((digest[i].toInt() and 0xFF) % 4)
            }
        }
        return rays
    }

    /**
     * Renders a crisp shareable PNG card and embeds a lossless PNG tEXt chunk ("TeronSunCode")
     * so importing the PNG image in another Teron phone decodes the full ECDH public key!
     */
    fun renderShareCardPng(profile: UserProfile): ByteArray {
        val w = 900
        val h = 1120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF080C16.toInt(), 0xFF111B33.toInt(), 0xFF0B1020.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Outer card border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF243B6B.toInt()
        }
        c.drawRoundRect(RectF(36f, 36f, w - 36f, h - 36f), 54f, 54f, borderPaint)

        // Header title
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("Ваш SunCode • Teron", w / 2f, 125f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("Наведите камеру или импортируйте карточку в Teron", w / 2f, 170f, subPaint)

        // Draw SunCode radial rays + central squircle
        val cx = w / 2f
        val cy = 530f
        drawSunCodeGraphic(c, cx, cy, 275f, profile, phase = 0f)

        // Name & Handle
        titlePaint.textSize = 46f
        c.drawText(profile.displayName, cx, 895f, titlePaint)

        val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.accentCyan
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("${profile.handle}  •  ${profile.nodeId}", cx, 950f, cyanPaint)

        subPaint.textSize = 22f
        c.drawText("ECDH P-256: ${app.teron.superapp.core.TeronCrypto.fingerprintHex(profile.publicKeyB64)}", cx, 1005f, subPaint)

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
            strokeWidth = (outerRadius * 0.032f).coerceAtLeast(4f)
        }

        // Subtle ambient glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Theme.withAlpha(profile.avatarColor, 38)
        }
        canvas.drawCircle(cx, cy, outerRadius * 0.92f, glowPaint)

        for (i in 0 until 64) {
            val angle = (i * (360.0 / 64.0) - 90.0) * Math.PI / 180.0
            val amp = rays[i]
            val pulse = 1.0f + 0.04f * sin((phase + i * 0.22).toDouble()).toFloat()
            val lenFactor = when (amp) {
                4 -> 0.42f
                3 -> 0.34f
                2 -> 0.25f
                1 -> 0.17f
                else -> 0.10f
            } * pulse
            val rStart = innerR + outerRadius * 0.05f
            val rEnd = rStart + outerRadius * lenFactor

            rayPaint.color = when {
                amp == 4 -> Theme.accentAmber
                i % 2 == 0 -> Theme.accentCyan
                else -> 0xFFA78BFA.toInt()
            }

            val x1 = cx + (rStart * cos(angle)).toFloat()
            val y1 = cy + (rStart * sin(angle)).toFloat()
            val x2 = cx + (rEnd * cos(angle)).toFloat()
            val y2 = cy + (rEnd * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, rayPaint)

            if (amp == 4) {
                val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.accentAmber }
                val dx = cx + ((rEnd + outerRadius * 0.04f) * cos(angle)).toFloat()
                val dy = cy + ((rEnd + outerRadius * 0.04f) * sin(angle)).toFloat()
                canvas.drawCircle(dx, dy, outerRadius * 0.022f, dotP)
            }
        }

        // Center Squircle Badge (inspired by SunFlower / SunBurst code design)
        val boxHalf = innerR * 0.78f
        val rect = RectF(cx - boxHalf, cy - boxHalf, cx + boxHalf, cy + boxHalf)
        val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(profile.avatarColor, Theme.accentIndigo),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, boxHalf * 0.44f, boxHalf * 0.44f, sqPaint)

        val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (outerRadius * 0.015f).coerceAtLeast(2.5f)
            color = 0x88FFFFFF.toInt()
        }
        canvas.drawRoundRect(rect, boxHalf * 0.44f, boxHalf * 0.44f, ringStroke)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = boxHalf * 0.85f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = glyphPaint.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(profile.avatarGlyph, cx, ty, glyphPaint)
    }

    private fun injectPngTextChunk(pngBytes: ByteArray, keyword: String, text: String): ByteArray {
        if (pngBytes.size < 33) return pngBytes
        val data = (keyword + "\u0000" + text).toByteArray(Charsets.UTF_8)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value.toInt()

        // Insert right before IEND (last 12 bytes of standard PNG)
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
            val rest = ByteArray( (bytes.size - start).coerceAtMost(2048) )
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
