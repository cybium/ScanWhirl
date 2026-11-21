package com.scanwhirl.app.ui.qr

import com.scanwhirl.app.R
import java.net.IDN
import java.net.URI

/** What kind of value a scanned code holds (design §3: QR code reader). */
enum class QrKind { URL, WIFI, CONTACT, PHONE, EMAIL, SMS, GEO, TEXT }

/** The actions the result screen offers for a [QrKind]. */
enum class QrAction { OPEN_LINK, ADD_CONTACT, CALL, COMPOSE_EMAIL, OPEN_MAP, COPY, SHARE }

/** One label/value row on the result screen. [secret] marks a Wi-Fi password. */
data class QrField(val labelRes: Int, val value: String, val secret: Boolean = false)

/**
 * Parsed result of a scanned code. [parse] is pure: it works on the raw text and the value
 * type the scanner reported, so every type and action choice is unit-testable without the
 * Google code scanner (design §3 "QR code reader").
 */
data class QrContent(
    val kind: QrKind,
    val title: String,
    val fields: List<QrField>,
    val actions: List<QrAction>,
    /** The exact scanned text, for Share. */
    val raw: String,
    /** What Copy puts on the clipboard (the Wi-Fi password for a network code). */
    val copyText: String,
    /** The Uri for the type's main action (link, tel:, mailto:, geo:), if any. */
    val actionUri: String?
) {

    companion object {
        /** A title shown big; longer values are cut with an ellipsis. */
        const val MAX_TITLE = 120

        /** Nothing longer than this is displayed or stored in the history. */
        const val MAX_VALUE = 4096

        /** Shown in place of a secret until the user chooses to reveal it. */
        const val MASK = "••••••••"

        /**
         * Redaction applied before a scanned value is written to the reversible (Base64) history.
         * A Wi-Fi code is rebuilt from the very same tokenizer [parseWifi] uses, with every field
         * that tokenizer reads as a password left out. That covers escaped (`\P:`), lower-case
         * and duplicate password fields, not just a literal `P:` prefix. Also applied on read so
         * entries saved by older versions never surface a password.
         */
        fun historyValue(raw: String, kind: QrKind): String {
            if (kind != QrKind.WIFI) return raw
            val prefixLength = if (raw.length >= 5 && raw.regionMatches(0, "WIFI:", 0, 5, true)) 5 else 0
            val kept = parseWifiFields(raw.substring(prefixLength)).filterNot { (key, _) -> key == 'P' }
            return raw.take(prefixLength) + buildString {
                kept.forEach { (key, value) ->
                    append(key).append(':').append(escapeWifiValue(value)).append(';')
                }
                append(';')
            }
        }

        private const val WIFI_PREFIX = "WIFI:"
        private const val VCARD_PREFIX = "BEGIN:VCARD"
        private const val MECARD_PREFIX = "MECARD:"

        /**
         * Classifies [rawValue] by its own prefix and builds the screen model. [reported] is
         * the scanner's value type (used when the text alone is ambiguous); TEXT is the
         * honest fallback for anything unrecognised.
         */
        /** [contactFallback] is the localised "Contact" title used when a card has no name. */
        fun parse(
            rawValue: String,
            reported: QrKind = QrKind.TEXT,
            contactFallback: String = "Contact"
        ): QrContent {
            val value = rawValue.take(MAX_VALUE).trim()
            val upper = value.uppercase()
            return when {
                upper.startsWith("HTTP://") || upper.startsWith("HTTPS://") -> url(value)
                upper.startsWith("WWW.") -> url(value)
                upper.startsWith("WIFI:") -> wifi(value)
                upper.startsWith(VCARD_PREFIX) -> vcard(value, contactFallback)
                upper.startsWith(MECARD_PREFIX) -> mecard(value, contactFallback)
                upper.startsWith("TEL:") -> phone(value.substring(4))
                upper.startsWith("MAILTO:") -> email(value.substring(7).substringBefore('?'))
                upper.startsWith("SMSTO:") || upper.startsWith("SMS:") -> sms(value)
                upper.startsWith("GEO:") -> geo(value.substring(4))
                reported != QrKind.TEXT -> byReported(reported, value, contactFallback)
                else -> text(value)
            }
        }

        private fun byReported(reported: QrKind, value: String, contactFallback: String): QrContent = when (reported) {
            QrKind.URL -> url(value)
            QrKind.WIFI -> wifi(value)
            QrKind.CONTACT -> if (value.uppercase().startsWith(MECARD_PREFIX)) {
                mecard(value, contactFallback)
            } else {
                vcard(value, contactFallback)
            }
            QrKind.PHONE -> phone(value)
            QrKind.EMAIL -> email(value.substringAfter("mailto:", value))
            QrKind.SMS -> sms(value)
            QrKind.GEO -> geo(value.substringAfter("geo:", value))
            QrKind.TEXT -> text(value)
        }

        private fun text(value: String): QrContent = QrContent(
            kind = QrKind.TEXT,
            title = clip(value),
            fields = emptyList(),
            actions = listOf(QrAction.COPY, QrAction.SHARE),
            raw = value,
            copyText = value,
            actionUri = null
        )

        private fun url(value: String): QrContent {
            val normalized = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                value
            } else {
                "https://${value.removePrefix("//")}"
            }
            return QrContent(
                kind = QrKind.URL,
                title = clip(normalized),
                // The real host only: never the user@host part a deceptive link can hide behind.
                fields = listOf(websiteField(value, normalized)),
                actions = listOf(QrAction.OPEN_LINK, QrAction.COPY, QrAction.SHARE),
                raw = value,
                copyText = normalized,
                actionUri = normalized
            )
        }

        /** A link always gets a row: either the real host, or the exact text for inspection. */
        private fun websiteField(scanned: String, normalized: String): QrField {
            val host = displayHost(normalized)
            return if (host != null) {
                QrField(R.string.qr_field_website, host)
            } else {
                // java.net.URI rejects backslashes, spaces and other illegal characters. Rather
                // than guess a host from such text, show verbatim what was on the code.
                QrField(R.string.qr_field_check_link, clip(scanned))
            }
        }

        /**
         * The host a link really goes to, decoded from punycode for reading. Both the Unicode
         * and ASCII forms are shown when they differ, so a mixed-script look-alike is visible.
         * Userinfo is never included: `https://trusted@evil/` shows "evil", not "trusted".
         */
        private fun displayHost(normalized: String): String? {
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() }
                ?: return null
            val unicode = runCatching { IDN.toUnicode(host) }.getOrDefault(host)
            val ascii = runCatching { IDN.toASCII(unicode) }.getOrDefault(host)
            return if (ascii.equals(unicode, ignoreCase = true)) unicode else "$unicode ($ascii)"
        }

        private fun wifi(value: String): QrContent {
            val fields = parseWifi(value.removePrefixPrefix(WIFI_PREFIX))
            val ssid = fields['S'].orEmpty()
            val password = fields['P'].orEmpty()
            val security = fields['T'].orEmpty()
            val rows = buildList {
                if (ssid.isNotEmpty()) add(QrField(R.string.qr_field_network, ssid))
                if (password.isNotEmpty()) add(QrField(R.string.qr_field_password, password, secret = true))
                if (security.isNotEmpty() && security.uppercase() != "NOPASS") {
                    add(QrField(R.string.qr_field_security, security))
                }
            }
            return QrContent(
                kind = QrKind.WIFI,
                title = clip(ssid.ifEmpty { value }),
                fields = rows,
                actions = listOf(QrAction.COPY),
                raw = value,
                copyText = password.ifEmpty { ssid }.ifEmpty { value },
                actionUri = null
            )
        }

        private fun vcard(value: String, contactFallback: String): QrContent {
            val lines = value
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace(Regex("\n[ \t]"), "") // folded lines
                .lines()
            val names = LinkedHashMap<String, MutableList<String>>()
            lines.forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).substringBefore(';').uppercase()
                val text = unescapeVcard(line.substring(colon + 1))
                if (key.isNotEmpty() && text.isNotEmpty()) {
                    names.getOrPut(key) { ArrayList() }.add(text)
                }
            }
            val rows = buildList {
                names["FN"]?.firstOrNull()?.let { add(QrField(R.string.qr_field_name, it)) }
                addAll(names["N"].orEmpty().map { QrField(R.string.qr_field_name, formatVcardName(it)) })
                addAll(names["TEL"].orEmpty().map { QrField(R.string.qr_field_phone, it) })
                addAll(names["EMAIL"].orEmpty().map { QrField(R.string.qr_field_email, it) })
                addAll(names["ORG"].orEmpty().map { QrField(R.string.qr_field_org, it) })
                names["TITLE"]?.firstOrNull()?.let { add(QrField(R.string.qr_field_role, it)) }
                names["ADR"]?.firstOrNull()?.let { add(QrField(R.string.qr_field_address, it.replace(';', ' ').trim())) }
            }.distinct()
            // BEGIN:VCARD is an envelope line, never a name. Use a real field, then "Contact".
            val displayName = names["FN"]?.firstOrNull()
                ?: names["N"]?.firstOrNull()?.let { formatVcardName(it) }
                ?: rows.firstOrNull()?.value
                ?: contactFallback
            return QrContent(
                kind = QrKind.CONTACT,
                title = clip(displayName),
                fields = rows,
                actions = listOf(QrAction.ADD_CONTACT, QrAction.COPY),
                raw = value,
                copyText = value,
                actionUri = null
            )
        }

        private fun mecard(value: String, contactFallback: String): QrContent {
            val pairs = splitEscaped(value.removePrefixPrefix(MECARD_PREFIX), ';')
            val map = LinkedHashMap<String, String>()
            pairs.forEach { pair ->
                val colon = pair.indexOf(':')
                if (colon > 0) map[pair.substring(0, colon).uppercase()] = pair.substring(colon + 1)
            }
            val name = map["N"]?.let { raw ->
                val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) parts.reversed().joinToString(" ") else parts.firstOrNull().orEmpty()
            }.orEmpty()
            val rows = buildList {
                if (name.isNotEmpty()) add(QrField(R.string.qr_field_name, name))
                if (map["ORG"]?.isNotEmpty() == true) add(QrField(R.string.qr_field_org, map.getValue("ORG")))
                if (map["TEL"]?.isNotEmpty() == true) add(QrField(R.string.qr_field_phone, map.getValue("TEL")))
                if (map["EMAIL"]?.isNotEmpty() == true) add(QrField(R.string.qr_field_email, map.getValue("EMAIL")))
                if (map["ADR"]?.isNotEmpty() == true) add(QrField(R.string.qr_field_address, map.getValue("ADR")))
            }
            return QrContent(
                kind = QrKind.CONTACT,
                title = clip(name.ifEmpty { rows.firstOrNull()?.value ?: contactFallback }),
                fields = rows,
                actions = listOf(QrAction.ADD_CONTACT, QrAction.COPY),
                raw = value,
                copyText = value,
                actionUri = null
            )
        }

        private fun phone(number: String): QrContent {
            val value = number.trim()
            return QrContent(
                kind = QrKind.PHONE,
                title = clip(value),
                fields = emptyList(),
                actions = listOf(QrAction.CALL, QrAction.COPY),
                raw = value,
                copyText = value,
                actionUri = "tel:$value"
            )
        }

        private fun email(address: String): QrContent {
            val value = address.trim()
            return QrContent(
                kind = QrKind.EMAIL,
                title = clip(value),
                fields = emptyList(),
                actions = listOf(QrAction.COMPOSE_EMAIL, QrAction.COPY),
                raw = value,
                copyText = value,
                actionUri = "mailto:$value"
            )
        }

        private fun sms(value: String): QrContent {
            val body = value.substringAfter(':', value)
            val number = body.substringBefore(':').trim()
            val message = body.substringAfter(':', "").trim()
            return QrContent(
                kind = QrKind.SMS,
                title = clip(number),
                fields = if (message.isEmpty()) emptyList() else listOf(QrField(R.string.qr_field_message, message)),
                actions = listOf(QrAction.COPY, QrAction.SHARE),
                raw = value,
                copyText = value,
                actionUri = null
            )
        }

        private fun geo(coordinates: String): QrContent {
            val value = coordinates.trim()
            return QrContent(
                kind = QrKind.GEO,
                title = clip(value),
                fields = emptyList(),
                actions = listOf(QrAction.OPEN_MAP, QrAction.COPY, QrAction.SHARE),
                raw = value,
                copyText = value,
                actionUri = "geo:$value"
            )
        }

        /** `WIFI:T:WPA;S:name;P:pass;H:true;;` with `\;`, `\,`, `\:` and `\\` escapes. */
        private fun parseWifi(body: String): Map<Char, String> {
            val map = LinkedHashMap<Char, String>()
            parseWifiFields(body).forEach { (key, value) -> map[key] = value }
            return map
        }

        /**
         * The single tokenizer behind both parsing and history redaction. It unescapes values
         * before reading a one-character key, so `\P:` and `p:` are passwords just like `P:`.
         */
        private fun parseWifiFields(body: String): List<Pair<Char, String>> {
            val fields = ArrayList<Pair<Char, String>>()
            val text = StringBuilder()
            var key: Char? = null
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> {
                        text.append(body[i + 1])
                        i++
                    }
                    c == ';' -> {
                        key?.let { fields += it to text.toString() }
                        key = null
                        text.setLength(0)
                    }
                    c == ':' && key == null && text.length == 1 -> {
                        key = text[0].uppercaseChar()
                        text.setLength(0)
                    }
                    else -> text.append(c)
                }
                i++
            }
            key?.let { fields += it to text.toString() }
            return fields
        }

        /** Re-escapes a parsed value when a Wi-Fi code is rebuilt for storage. */
        private fun escapeWifiValue(value: String): String = buildString {
            value.forEach { c ->
                if (c == '\\' || c == ';' || c == ',' || c == ':') append('\\')
                append(c)
            }
        }

        /** Splits on [separator] while honouring backslash escapes. */
        private fun splitEscaped(body: String, separator: Char): List<String> {
            val parts = ArrayList<String>()
            val text = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length -> {
                        text.append(body[i + 1])
                        i++
                    }
                    c == separator -> {
                        parts += text.toString()
                        text.setLength(0)
                    }
                    else -> text.append(c)
                }
                i++
            }
            if (text.isNotEmpty()) parts += text.toString()
            return parts
        }

        private fun unescapeVcard(value: String): String =
            value.replace("\\n", "\n", ignoreCase = true)
                .replace("\\,", ",")
                .replace("\\;", ";")
                .replace("\\\\", "\\")
                .trim()

        /** `Doe;John;;;` or `Doe,John` becomes "John Doe". */
        private fun formatVcardName(value: String): String {
            val parts = if (value.contains(';')) {
                value.split(';')
            } else {
                value.split(',')
            }.map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return parts.firstOrNull().orEmpty()
            return parts.reversed().joinToString(" ")
        }

        private fun clip(value: String): String =
            if (value.length <= MAX_TITLE) value else value.take(MAX_TITLE - 1) + "…"

        /** `removePrefix` is case sensitive; the prefix checks above are not. */
        private fun String.removePrefixPrefix(prefix: String): String =
            if (regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                substring(prefix.length)
            } else {
                this
            }
    }
}
