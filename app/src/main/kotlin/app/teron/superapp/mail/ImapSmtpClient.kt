package app.teron.superapp.mail

import android.util.Base64
import app.teron.superapp.core.MailAccountConfig
import app.teron.superapp.core.TeronDatabase
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object ImapSmtpClient {

    data class ServerPreset(
        val imapHost: String,
        val imapPort: Int,
        val smtpHost: String,
        val smtpPort: Int
    )

    fun suggestServers(email: String): ServerPreset {
        val domain = email.substringAfter('@', "").trim().lowercase()
        return when (domain) {
            "gmail.com", "googlemail.com" -> ServerPreset("imap.gmail.com", 993, "smtp.gmail.com", 465)
            "yandex.ru", "ya.ru", "yandex.kz", "yandex.com", "yandex.by" -> ServerPreset("imap.yandex.ru", 993, "smtp.yandex.ru", 465)
            "mail.ru", "inbox.ru", "bk.ru", "list.ru", "internet.ru" -> ServerPreset("imap.mail.ru", 993, "smtp.mail.ru", 465)
            "rambler.ru", "lenta.ru", "ro.ru" -> ServerPreset("imap.rambler.ru", 993, "smtp.rambler.ru", 465)
            "icloud.com", "me.com", "mac.com" -> ServerPreset("imap.mail.me.com", 993, "smtp.mail.me.com", 587)
            "outlook.com", "hotmail.com", "live.com" -> ServerPreset("outlook.office365.com", 993, "smtp.office365.com", 587)
            "" -> ServerPreset("", 993, "", 465)
            else -> ServerPreset("imap.$domain", 993, "smtp.$domain", 465)
        }
    }

    // =========================================================================
    // 1. REAL IMAP4 OVER SSL (PORT 993) INBOX SYNCHRONIZATION
    // =========================================================================
    fun syncInbox(cfg: MailAccountConfig, db: TeronDatabase): Pair<Boolean, String> {
        if (!cfg.isConfigured) {
            return false to "Укажите почтовый адрес, пароль приложения и сервер IMAP/SMTP"
        }

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(cfg.imapHost, cfg.imapPort), 8_000)
            val sock = sslFactory.createSocket(plain, cfg.imapHost, cfg.imapPort, true) as SSLSocket
            sock.soTimeout = 10_000
            sock.startHandshake()

            sock.use { s ->
                val ins = BufferedInputStream(s.inputStream)
                val out = s.outputStream

                // Server greeting
                val greeting = readLineAscii(ins)
                if (!greeting.startsWith("* OK", ignoreCase = true)) {
                    return false to "Сервер IMAP не готов: $greeting"
                }

                // Login
                val escUser = escapeImapQuoted(cfg.email)
                val escPass = escapeImapQuoted(cfg.password)
                writeCmd(out, "A01 LOGIN \"$escUser\" \"$escPass\"")
                val loginResp = readUntilTagged(ins, "A01")
                if (!loginResp.second.startsWith("A01 OK", ignoreCase = true)) {
                    return false to "Ошибка авторизации IMAP (проверьте логин или пароль приложения)"
                }

                // Select INBOX
                writeCmd(out, "A02 SELECT INBOX")
                val selectResp = readUntilTagged(ins, "A02")
                if (!selectResp.second.startsWith("A02 OK", ignoreCase = true)) {
                    writeCmd(out, "A99 LOGOUT")
                    return false to "Не удалось открыть папку INBOX"
                }

                val existsMatch = Regex("\\*\\s+(\\d+)\\s+EXISTS", RegexOption.IGNORE_CASE).find(selectResp.first)
                val totalMsgs = existsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (totalMsgs == 0) {
                    writeCmd(out, "A99 LOGOUT")
                    return true to "Ящик синхронизирован (входящих писем: 0)"
                }

                val startSeq = (totalMsgs - 19).coerceAtLeast(1)
                var importedCount = 0

                for (seq in totalMsgs downTo startSeq) {
                    val tag = "AF$seq"
                    writeCmd(
                        out,
                        "$tag FETCH $seq (UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID Content-Type Content-Transfer-Encoding)] BODY.PEEK[TEXT]<0.16384>)"
                    )
                    val rawMsgBlock = readTaggedWithLiterals(ins, tag)
                    val parsed = parseImapFetchBlock(rawMsgBlock, cfg.email, seq)
                    if (parsed != null) {
                        val added = db.insertMailIfNew(
                            packetId = parsed.packetId,
                            folder = "INBOX",
                            fromName = parsed.fromName,
                            fromAddress = parsed.fromAddress,
                            toAddress = parsed.toAddress,
                            subject = parsed.subject,
                            body = parsed.body,
                            timestampMs = parsed.timestampMs,
                            isRead = parsed.seen,
                            viaAirMesh = false,
                            hopCount = 0,
                            priority = "NORMAL"
                        )
                        if (added) importedCount++
                    }
                }

                writeCmd(out, "A99 LOGOUT")
                return true to "Синхронизация завершена (загружено новых: $importedCount, всего в ящике: $totalMsgs)"
            }
        } catch (e: Throwable) {
            try { plain.close() } catch (_: Throwable) {}
            return false to "Ошибка подключения IMAP (${cfg.imapHost}:${cfg.imapPort}): ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // =========================================================================
    // 2. REAL SMTP OVER SSL (465) OR STARTTLS (587) EMAIL SENDING
    // =========================================================================
    fun sendEmail(
        cfg: MailAccountConfig,
        toAddress: String,
        subject: String,
        body: String,
        db: TeronDatabase
    ): Pair<Boolean, String> {
        if (!cfg.isConfigured) {
            return false to "Сначала подключите почтовый аккаунт IMAP/SMTP"
        }
        val cleanTo = toAddress.trim()
        if (!cleanTo.contains("@")) {
            return false to "Некорректный адрес получателя"
        }

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        var rawSock: Socket? = null
        try {
            val activeSocket: Socket = if (cfg.smtpPort == 465) {
                val p = Socket()
                rawSock = p
                p.connect(InetSocketAddress(cfg.smtpHost, cfg.smtpPort), 8_000)
                (sslFactory.createSocket(p, cfg.smtpHost, cfg.smtpPort, true) as SSLSocket).apply {
                    soTimeout = 10_000
                    startHandshake()
                }
            } else {
                val p = Socket()
                rawSock = p
                p.connect(InetSocketAddress(cfg.smtpHost, cfg.smtpPort), 8_000)
                p.soTimeout = 10_000
                val pIn = BufferedInputStream(p.inputStream)
                val pOut = p.outputStream
                readSmtpResponse(pIn)
                writeCmd(pOut, "EHLO teron.app")
                readSmtpResponse(pIn)
                writeCmd(pOut, "STARTTLS")
                val tlsResp = readSmtpResponse(pIn)
                if (!tlsResp.startsWith("220")) {
                    return false to "Сервер SMTP не поддержал STARTTLS: $tlsResp"
                }
                (sslFactory.createSocket(p, cfg.smtpHost, cfg.smtpPort, true) as SSLSocket).apply {
                    soTimeout = 10_000
                    startHandshake()
                }
            }

            activeSocket.use { s ->
                val ins = BufferedInputStream(s.inputStream)
                val out = s.outputStream

                if (cfg.smtpPort == 465) {
                    val banner = readSmtpResponse(ins)
                    if (!banner.startsWith("220")) return false to "SMTP сервер недоступен: $banner"
                }

                writeCmd(out, "EHLO teron.app")
                val ehlo = readSmtpResponse(ins)
                if (!ehlo.startsWith("250")) return false to "Ошибка EHLO: $ehlo"

                writeCmd(out, "AUTH LOGIN")
                val authStep1 = readSmtpResponse(ins)
                if (!authStep1.startsWith("334")) return false to "Сервер не принимает AUTH LOGIN: $authStep1"

                writeCmd(out, Base64.encodeToString(cfg.email.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                val authStep2 = readSmtpResponse(ins)
                if (!authStep2.startsWith("334")) return false to "Ошибка логина SMTP: $authStep2"

                writeCmd(out, Base64.encodeToString(cfg.password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                val authFinal = readSmtpResponse(ins)
                if (!authFinal.startsWith("235")) {
                    return false to "Неверный пароль SMTP (для Gmail/Яндекс/Mail.ru используйте пароль приложения)"
                }

                writeCmd(out, "MAIL FROM:<${cfg.email}>")
                val mf = readSmtpResponse(ins)
                if (!mf.startsWith("250")) return false to "Отклонён отправитель: $mf"

                writeCmd(out, "RCPT TO:<$cleanTo>")
                val rt = readSmtpResponse(ins)
                if (!rt.startsWith("250") && !rt.startsWith("251")) return false to "Отклонён получатель: $rt"

                writeCmd(out, "DATA")
                val dataGo = readSmtpResponse(ins)
                if (!dataGo.startsWith("354")) return false to "Ошибка команды DATA: $dataGo"

                val msgId = "<${UUID.randomUUID()}@${cfg.email.substringAfter('@', "teron.app")}>"
                val dateHeader = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
                val encSubject = encodeRfc2047(subject.ifBlank { "(Без темы)" })
                val encFromName = encodeRfc2047(cfg.senderName.ifBlank { cfg.email.substringBefore('@') })
                val bodyB64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.CRLF)

                val mimeMessage = buildString {
                    append("Date: $dateHeader\r\n")
                    append("From: $encFromName <${cfg.email}>\r\n")
                    append("To: <$cleanTo>\r\n")
                    append("Subject: $encSubject\r\n")
                    append("Message-ID: $msgId\r\n")
                    append("MIME-Version: 1.0\r\n")
                    append("Content-Type: text/plain; charset=UTF-8\r\n")
                    append("Content-Transfer-Encoding: base64\r\n")
                    append("\r\n")
                    append(bodyB64)
                    if (!bodyB64.endsWith("\r\n")) append("\r\n")
                    append(".")
                }
                writeCmd(out, mimeMessage)
                val queuedResp = readSmtpResponse(ins)
                writeCmd(out, "QUIT")

                if (!queuedResp.startsWith("250")) {
                    return false to "Сервер не принял письмо: $queuedResp"
                }

                db.insertMailIfNew(
                    packetId = "smtp-$msgId",
                    folder = "SENT",
                    fromName = cfg.senderName.ifBlank { cfg.email },
                    fromAddress = cfg.email,
                    toAddress = cleanTo,
                    subject = subject,
                    body = body,
                    timestampMs = System.currentTimeMillis(),
                    isRead = true,
                    viaAirMesh = false,
                    hopCount = 0,
                    priority = "NORMAL"
                )
                return true to "Письмо отправлено на $cleanTo"
            }
        } catch (e: Throwable) {
            try { rawSock?.close() } catch (_: Throwable) {}
            return false to "Ошибка отправки SMTP: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // =========================================================================
    // 3. MIME, RFC-2047 & IMAP WIRE PROTOCOL PARSERS
    // =========================================================================
    private data class ParsedImapMail(
        val packetId: String,
        val fromName: String,
        val fromAddress: String,
        val toAddress: String,
        val subject: String,
        val body: String,
        val timestampMs: Long,
        val seen: Boolean
    )

    private fun parseImapFetchBlock(literalsAndLines: Pair<String, List<ByteArray>>, accountEmail: String, seq: Int): ParsedImapMail? {
        val metaLine = literalsAndLines.first
        val parts = literalsAndLines.second
        if (parts.isEmpty()) return null

        val seen = metaLine.contains("\\Seen", ignoreCase = true)
        val uidMatch = Regex("UID\\s+(\\d+)", RegexOption.IGNORE_CASE).find(metaLine)
        val uid = uidMatch?.groupValues?.get(1) ?: seq.toString()

        val headerText = String(parts[0], Charsets.ISO_8859_1)
        val unfoldedHeaders = headerText.replace(Regex("\r?\n[ \t]+"), " ")

        fun headerVal(name: String): String {
            val m = Regex("^$name:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(unfoldedHeaders)
            return m?.groupValues?.get(1)?.trim().orEmpty()
        }

        val rawFrom = decodeRfc2047(headerVal("From"))
        val emailMatch = Regex("<([^>]+)>").find(rawFrom)
        val fromAddr = emailMatch?.groupValues?.get(1)?.trim() ?: rawFrom.trim()
        val fromName = rawFrom.substringBefore('<').trim().trim('"').ifBlank { fromAddr.substringBefore('@') }

        val rawTo = decodeRfc2047(headerVal("To")).ifBlank { accountEmail }
        val subject = decodeRfc2047(headerVal("Subject")).ifBlank { "(Без темы)" }
        val msgId = headerVal("Message-ID").ifBlank { "imap-$accountEmail-$uid" }
        val dateStr = headerVal("Date")
        val contentType = headerVal("Content-Type")
        val transferEnc = headerVal("Content-Transfer-Encoding")

        val bodyRawBytes = if (parts.size >= 2) parts[1] else ByteArray(0)
        val cleanBody = decodeMimeBody(bodyRawBytes, contentType, transferEnc)

        return ParsedImapMail(
            packetId = "imap-${accountEmail.lowercase()}-$uid-${msgId.hashCode()}",
            fromName = fromName,
            fromAddress = fromAddr,
            toAddress = rawTo,
            subject = subject,
            body = cleanBody.ifBlank { "(Пустое сообщение)" },
            timestampMs = parseRfc2822Date(dateStr),
            seen = seen
        )
    }

    private fun decodeMimeBody(rawBytes: ByteArray, contentTypeHeader: String, transferEncodingHeader: String): String {
        if (rawBytes.isEmpty()) return ""
        val rawLatin = String(rawBytes, Charsets.ISO_8859_1)

        // Check multipart boundary
        val bMatch = Regex("boundary=\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE).find(contentTypeHeader)
        if (bMatch != null) {
            val boundary = "--" + bMatch.groupValues[1]
            val sections = rawLatin.split(boundary)
            var htmlFallback = ""
            for (sec in sections) {
                val sepIdx = sec.indexOf("\r\n\r\n").let { if (it >= 0) it else sec.indexOf("\n\n") }
                if (sepIdx < 0) continue
                val pHead = sec.substring(0, sepIdx).replace(Regex("\r?\n[ \t]+"), " ")
                val pBody = sec.substring(sepIdx).trimStart('\r', '\n').substringBeforeLast("--")
                val pType = Regex("Content-Type:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE).find(pHead)?.groupValues?.get(1).orEmpty()
                val pEnc = Regex("Content-Transfer-Encoding:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE).find(pHead)?.groupValues?.get(1).orEmpty()
                if (pType.contains("text/plain", ignoreCase = true)) {
                    val decoded = decodeSinglePart(pBody.toByteArray(Charsets.ISO_8859_1), pType, pEnc).trim()
                    if (decoded.isNotEmpty()) return decoded
                } else if (pType.contains("text/html", ignoreCase = true) && htmlFallback.isEmpty()) {
                    htmlFallback = stripHtml(decodeSinglePart(pBody.toByteArray(Charsets.ISO_8859_1), pType, pEnc))
                }
            }
            if (htmlFallback.isNotEmpty()) return htmlFallback
        }

        val single = decodeSinglePart(rawBytes, contentTypeHeader, transferEncodingHeader)
        return if (contentTypeHeader.contains("text/html", ignoreCase = true) || single.contains("<html", ignoreCase = true)) {
            stripHtml(single)
        } else {
            single.trim()
        }
    }

    private fun decodeSinglePart(bytes: ByteArray, contentType: String, transferEnc: String): String {
        val csName = Regex("charset=\"?([^\"\\s;]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1) ?: "UTF-8"
        val charset = try { Charset.forName(csName) } catch (_: Throwable) { Charsets.UTF_8 }
        val enc = transferEnc.trim().lowercase()

        val decodedBytes = when {
            enc.contains("base64") -> {
                try {
                    val cleaned = String(bytes, Charsets.US_ASCII).replace(Regex("[^A-Za-z0-9+/=]"), "")
                    Base64.decode(cleaned, Base64.DEFAULT)
                } catch (_: Throwable) { bytes }
            }
            enc.contains("quoted-printable") -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
        return String(decodedBytes, charset)
    }

    private fun decodeQuotedPrintable(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            if (b == '='.code && i + 2 < input.size) {
                val c1 = input[i + 1].toInt().toChar()
                val c2 = input[i + 2].toInt().toChar()
                if (c1 == '\r' && c2 == '\n') {
                    i += 3
                    continue
                } else if (c1 == '\n') {
                    i += 2
                    continue
                }
                val hex = "$c1$c2"
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun encodeRfc2047(text: String): String {
        if (text.all { it.code in 32..126 }) return text
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "=?UTF-8?B?$b64?="
    }

    fun decodeRfc2047(raw: String): String {
        if (!raw.contains("=?")) return raw
        val regex = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        val collapsed = raw.replace(Regex("\\?=\\s+=\\?"), "?==?")
        return regex.replace(collapsed) { m ->
            val csName = m.groupValues[1]
            val mode = m.groupValues[2].uppercase()
            val payload = m.groupValues[3]
            val cs = try { Charset.forName(csName) } catch (_: Throwable) { Charsets.UTF_8 }
            try {
                if (mode == "B") {
                    String(Base64.decode(payload, Base64.DEFAULT), cs)
                } else {
                    val qp = payload.replace('_', ' ').toByteArray(Charsets.ISO_8859_1)
                    String(decodeQuotedPrintable(qp), cs)
                }
            } catch (_: Throwable) {
                payload
            }
        }
    }

    private fun parseRfc2822Date(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        val cleaned = dateStr.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z"
        )
        for (fmt in formats) {
            try {
                val d = SimpleDateFormat(fmt, Locale.US).parse(cleaned)
                if (d != null) return d.time
            } catch (_: Throwable) {}
        }
        return System.currentTimeMillis()
    }

    private fun escapeImapQuoted(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun writeCmd(out: OutputStream, line: String) {
        out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun readLineAscii(ins: InputStream): String {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val b = ins.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return String(baos.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readUntilTagged(ins: InputStream, tag: String): Pair<String, String> {
        val lines = StringBuilder()
        while (true) {
            val line = readLineAscii(ins)
            if (line.isEmpty() && lines.length > 65536) return lines.toString() to "$tag BAD"
            if (line.startsWith("$tag ", ignoreCase = true)) {
                return lines.toString() to line
            }
            lines.append(line).append('\n')
        }
    }

    private fun readTaggedWithLiterals(ins: InputStream, tag: String): Pair<String, List<ByteArray>> {
        val meta = StringBuilder()
        val literals = ArrayList<ByteArray>(2)
        val litRegex = Regex("\\{(\\d+)\\}$")
        while (true) {
            val line = readLineAscii(ins)
            if (line.startsWith("$tag ", ignoreCase = true)) {
                return meta.toString() to literals
            }
            meta.append(line).append('\n')
            val m = litRegex.find(line)
            if (m != null) {
                val count = m.groupValues[1].toIntOrNull() ?: 0
                if (count in 1..262_144) {
                    val buf = ByteArray(count)
                    var readTotal = 0
                    while (readTotal < count) {
                        val r = ins.read(buf, readTotal, count - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    literals.add(buf)
                }
            }
        }
    }

    private fun readSmtpResponse(ins: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val line = readLineAscii(ins)
            sb.append(line)
            if (line.length < 4 || line[3] != '-') break
            sb.append('\n')
        }
        return sb.toString()
    }
}
