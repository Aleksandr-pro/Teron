package app.teron.superapp.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import app.teron.superapp.core.PhoneBookEntry
import java.security.MessageDigest
import java.util.Locale

object PhoneContactsHelper {

    fun hasContactsPermission(ctx: Context): Boolean {
        return ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Normalizes CIS (KZ/RU) and international phone numbers to canonical E.164 digits without '+'.
     * Examples:
     *   "+7 (701) 555-12-34" -> "77015551234"
     *   "8 701 555 12 34"    -> "77015551234"
     *   "8 (916) 123-45-67"  -> "79161234567"
     *   "701 555 1234"       -> "77015551234"
     */
    fun canonicalizePhone(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder()
        for (ch in trimmed) {
            if (ch in '0'..'9') sb.append(ch)
        }
        var digits = sb.toString()
        if (digits.startsWith("00") && digits.length >= 11) {
            digits = digits.substring(2)
        }
        if (digits.length == 11 && digits.startsWith("8") && (digits[1] == '7' || digits[1] == '9' || digits[1] == '4' || digits[1] == '3')) {
            digits = "7" + digits.substring(1)
        } else if (digits.length == 10 && (digits.startsWith("7") || digits.startsWith("9"))) {
            digits = "7$digits"
        }
        if (digits.length !in 7..15) return ""
        return digits
    }

    fun formatPhoneForDisplay(rawOrCanonical: String): String {
        val c = canonicalizePhone(rawOrCanonical)
        if (c.isEmpty()) return rawOrCanonical.trim()
        if (c.length == 11 && c.startsWith("7")) {
            return "+7 (${c.substring(1, 4)}) ${c.substring(4, 7)}-${c.substring(7, 9)}-${c.substring(9, 11)}"
        }
        return "+$c"
    }

    /**
     * Domain-separated SHA-256 hash so raw phone numbers are never transmitted to the directory.
     */
    fun computePhoneHash(rawOrCanonical: String): String {
        val canonical = canonicalizePhone(rawOrCanonical)
        if (canonical.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("teron-phone-v1:$canonical".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(64)
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b))
        }
        return hex.toString()
    }

    fun readDeviceContacts(ctx: Context): List<PhoneBookEntry> {
        if (!hasContactsPermission(ctx)) return emptyList()
        val byCanonical = LinkedHashMap<String, PhoneBookEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val rawName = if (nameCol >= 0) cursor.getString(nameCol)?.trim().orEmpty() else ""
                    val rawNum = if (numCol >= 0) cursor.getString(numCol)?.trim().orEmpty() else ""
                    val canonical = canonicalizePhone(rawNum)
                    if (canonical.isEmpty()) continue
                    if (byCanonical.containsKey(canonical)) continue
                    val hash = computePhoneHash(canonical)
                    if (hash.isEmpty()) continue
                    byCanonical[canonical] = PhoneBookEntry(
                        canonicalPhone = canonical,
                        rawPhone = formatPhoneForDisplay(canonical),
                        displayName = rawName.ifBlank { formatPhoneForDisplay(canonical) },
                        phoneHash = hash
                    )
                }
            }
        } catch (_: Throwable) {}
        return byCanonical.values.toList()
    }
}
