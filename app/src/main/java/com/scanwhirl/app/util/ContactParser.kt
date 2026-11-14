package com.scanwhirl.app.util

import java.util.Locale

/**
 * Pure parser for business-card OCR text. It never touches Android, so the rules are unit-tested
 * on realistic card texts (including common OCR noise such as `O` for `0`, `l` for `1`, spaced
 * digits and `Ph:` / `M:` / `E-mail` / `www.` labels).
 *
 * The result is a [Contact] the editor prefills; every field can be corrected by the user before
 * the card is saved to Contacts, shared as a vCard 3.0 or kept as a document.
 */
object ContactParser {

    data class Contact(
        val name: String = "",
        val company: String = "",
        val jobTitle: String = "",
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val website: String = "",
        val address: String = ""
    )

    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val DOMAIN = Regex(
        "(?i)(?:https?://)?(?:www\\.)?[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" +
            "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"
    )
    private val PHONE_CHARS = Regex("[+()0-9OoIl|.\\-\\s]{8,}")

    private val PHONE_LABELS = Regex(
        "(?i)\\b(ph|phone|tel|telephone|mob|mobile|m|cell|call|contact|office|fax)\\b\\s*[:.]?\\s*"
    )
    private val EMAIL_LABELS = Regex("(?i)\\b(e-?mail|email|mail)\\b\\s*[:.]?\\s*")
    private val WEB_LABELS = Regex("(?i)\\b(web|website|url|site|w)\\b\\s*[:.]?\\s*")

    private val TITLE_WORDS = listOf(
        "manager", "engineer", "director", "founder", "co-founder", "ceo", "cto", "cfo", "coo",
        "md", "vp", "president", "owner", "partner", "proprietor", "consultant", "officer",
        "head", "lead", "sales", "marketing", "designer", "developer", "analyst", "executive",
        "specialist", "accountant", "architect", "professor", "teacher", "nurse", "doctor",
        "advocate", "lawyer", "intern", "trainee", "assistant", "supervisor", "technician"
    )
    private val COMPANY_WORDS = listOf(
        "pvt", "private", "ltd", "limited", "llp", "inc", "llc", "corp", "corporation",
        "company", "co.", "enterprises", "enterprise", "solutions", "technologies", "technology",
        "services", "industries", "group", "studio", "associates", "traders", "agency", "bank",
        "hospital", "school", "college", "university", "foundation", "labs", "systems", "infotech"
    )
    private val ADDRESS_WORDS = listOf(
        "road", "rd", "street", "st.", "nagar", "colony", "sector", "floor", "block", "area",
        "lane", "layout", "cross", "main", "industrial", "park", "estate", "plaza", "tower",
        "building", "bldg", "opposite", "opp.", "near", "post", "po", "pin", "zip", "india",
        "mumbai", "delhi", "bengaluru", "bangalore", "pune", "chennai", "hyderabad", "kolkata",
        "ahmedabad", "jaipur", "lucknow", "noida", "gurgaon", "gurugram", "kochi", "coimbatore"
    )

    /**
     * Parses [text] into a contact. Empty fields stay empty; nothing throws: a card with no
     * phone simply has no phone. Lines that are only a label ("Ph:", "E-mail:") are ignored.
     */
    fun parse(text: String): Contact {
        val lines = text.replace('\u00A0', ' ').split('\n')
            .map(::cleanLine)
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Contact()

        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var website = ""
        val addressParts = mutableListOf<String>()
        val remaining = mutableListOf<String>()

        for (line in lines) {
            var rest = line
            val foundEmails = EMAIL.findAll(rest).map { it.value }.toList()
            if (foundEmails.isNotEmpty()) {
                foundEmails.forEach { email ->
                    val normalized = email.trimEnd('.', ',', ';').lowercase(Locale.ROOT)
                    if (normalized !in emails) emails += normalized
                }
                rest = rest.replace(EMAIL, " ").replace(EMAIL_LABELS, " ")
            }
            val site = websiteOf(rest)
            if (site != null) {
                if (website.isEmpty()) website = site
                rest = rest.replace(site, " ").replace(WEB_LABELS, " ")
            }
            val foundPhones = phonesOf(rest)
            if (foundPhones.isNotEmpty()) {
                foundPhones.forEach { if (it !in phones) phones += it }
                rest = rest.replace(PHONE_CHARS, " ").replace(PHONE_LABELS, " ")
            }
            val leftover = cleanLine(rest)
            if (leftover.isEmpty() || isLabelOnly(leftover)) continue
            if (looksLikeAddress(leftover)) {
                addressParts += leftover.trimEnd(',', ';')
            } else {
                remaining += leftover
            }
        }

        var name = ""
        var company = ""
        var jobTitle = ""
        val leftovers = mutableListOf<String>()
        for (line in remaining) {
            when {
                jobTitle.isEmpty() && looksLikeTitle(line) -> jobTitle = line
                company.isEmpty() && looksLikeCompany(line) -> company = line
                name.isEmpty() && looksLikeName(line) -> name = line
                else -> leftovers += line
            }
        }
        if (company.isEmpty()) {
            company = leftovers.firstOrNull { looksLikeCompany(it) || it.split(' ').size <= 5 }
                ?.also { leftovers.remove(it) }
                ?: ""
        }
        if (name.isEmpty()) {
            name = leftovers.firstOrNull { looksLikeName(it) } ?: ""
        }
        if (addressParts.isEmpty()) {
            addressParts += leftovers.filter { it.any(Char::isDigit) && !looksLikePhoneLine(it) }
        }
        if (company.isEmpty() && name.isNotEmpty()) {
            company = leftovers.firstOrNull { it != name && it.split(' ').size <= 6 } ?: ""
        }
        if (jobTitle.isEmpty()) {
            jobTitle = remaining.firstOrNull { looksLikeTitle(it) } ?: ""
        }

        return Contact(
            name = name,
            company = company,
            jobTitle = jobTitle,
            phones = phones,
            emails = emails,
            website = website,
            address = addressParts.joinToString(", ")
        )
    }

    /** vCard 3.0 (RFC 2426) text with CRLF line endings and escaped field values. */
    fun vCard(contact: Contact): String {
        val out = StringBuilder()
        out.append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        val name = contact.name.trim()
        if (name.isNotEmpty()) {
            out.append("FN:").append(escape(name)).append("\r\n")
            val parts = name.split(' ').filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                out.append("N:").append(escape(parts.last())).append(';')
                    .append(escape(parts.dropLast(1).joinToString(" "))).append(";;;\r\n")
            } else {
                out.append("N:").append(escape(name)).append(";;;;\r\n")
            }
        }
        if (contact.company.isNotBlank()) out.append("ORG:").append(escape(contact.company)).append("\r\n")
        if (contact.jobTitle.isNotBlank()) out.append("TITLE:").append(escape(contact.jobTitle)).append("\r\n")
        contact.phones.forEachIndexed { index, phone ->
            val type = if (index == 0) "CELL" else "WORK"
            out.append("TEL;TYPE=").append(type).append(':').append(escape(phone)).append("\r\n")
        }
        contact.emails.forEach { out.append("EMAIL;TYPE=INTERNET:").append(escape(it)).append("\r\n") }
        if (contact.website.isNotBlank()) out.append("URL:").append(escape(contact.website)).append("\r\n")
        if (contact.address.isNotBlank()) {
            out.append("ADR;TYPE=WORK:;;").append(escape(contact.address)).append(";;;;\r\n")
        }
        out.append("END:VCARD\r\n")
        return out.toString()
    }

    /** RFC 2426 escaping: backslash, semicolon, comma and line breaks. */
    internal fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /** "98765 43210" for an Indian mobile, "+<digits>" otherwise. */
    internal fun normalizePhone(raw: String): String? {
        val fixed = StringBuilder(raw.length)
        raw.forEach { c ->
            when (c) {
                'O', 'o', 'Q', 'D' -> fixed.append('0')
                'l', 'I', '|', '!' -> fixed.append('1')
                else -> fixed.append(c)
            }
        }
        var digits = fixed.toString().filter(Char::isDigit)
        val plus = fixed.toString().trimStart().startsWith("+")
        if (digits.length < 8 || digits.length > 15) return null
        if (plus) {
            if (digits.length == 12 && digits.startsWith("91")) {
                val local = digits.drop(2)
                if (local.firstOrNull() in '6'..'9') {
                    return "+91 ${local.take(5)} ${local.takeLast(5)}"
                }
            }
            return "+$digits"
        }
        if (digits.startsWith("00") && digits.length in 12..17) {
            digits = digits.drop(2)
            return "+$digits"
        }
        if (digits.length == 12 && digits.startsWith("91")) {
            val local = digits.drop(2)
            return if (local.firstOrNull()?.let { it in '6'..'9' } == true) {
                "+91 ${local.take(5)} ${local.takeLast(5)}"
            } else {
                "+$digits"
            }
        }
        if (digits.length == 11 && digits.startsWith("0")) {
            val local = digits.drop(1)
            return if (local.firstOrNull()?.let { it in '6'..'9' } == true) {
                "${local.take(5)} ${local.takeLast(5)}"
            } else {
                digits.take(3) + " " + digits.substring(3, 7) + " " + digits.drop(7)
            }
        }
        if (digits.length == 10 && digits.firstOrNull()?.let { it in '6'..'9' } == true) {
            return "${digits.take(5)} ${digits.takeLast(5)}"
        }
        return digits
    }

    // ---- line classification ---------------------------------------------

    private fun cleanLine(line: String): String {
        var text = line.replace('\u00A0', ' ').trim()
        text = text.trimStart('•', '·', '-', '–', '—', '*', '|', ' ')
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun phonesOf(line: String): List<String> {
        // Cards separate numbers with "/", ";" or "or"; those are not phone characters, so the
        // regex already yields one candidate per number.
        val candidates = PHONE_CHARS.findAll(line.replace(" or ", " / ")).map { it.value }.toList()
        val hasLabel = PHONE_LABELS.containsMatchIn(line)
        val found = mutableListOf<String>()
        for (candidate in candidates) {
            val digitCount = candidate.count(Char::isDigit)
            val looksInternational = candidate.trimStart().startsWith("+")
            if (digitCount >= 10 || (hasLabel && digitCount >= 8) || (looksInternational && digitCount >= 8)) {
                normalizePhone(candidate)?.let { if (it !in found) found += it }
            }
        }
        return found
    }

    private fun websiteOf(line: String): String? {
        val lower = line.lowercase(Locale.ROOT)
        if (lower.contains('@')) return null
        val labeled = WEB_LABELS.find(line)
        val candidateText = if (labeled != null) line.substring(labeled.range.last + 1) else line
        val match = DOMAIN.find(candidateText) ?: return null
        val value = match.value.trimEnd('.', ',', ';')
        if (labeled == null) {
            val looksLikeWeb = value.startsWith("www.", ignoreCase = true) ||
                value.startsWith("http", ignoreCase = true)
            if (!looksLikeWeb) return null
        }
        return value.substringBefore(' ').trimEnd('.', ',', ';')
    }

    private fun isLabelOnly(line: String): Boolean =
        Regex("(?i)^(ph|phone|tel|telephone|mob|mobile|m|cell|call|fax|e-?mail|mail|web|website|url|w)\\s*[:.]?$")
            .matches(line)

    private fun looksLikePhoneLine(line: String): Boolean =
        line.count(Char::isDigit) >= 8 && line.count { it.isLetter() } <= 4

    private fun looksLikeTitle(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return TITLE_WORDS.any { lower.contains(it) } && line.split(' ').size <= 6 &&
            !looksLikeAddress(line) && '@' !in line
    }

    private fun looksLikeCompany(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (COMPANY_WORDS.any { lower.contains(it) }) return true
        // ALL-CAPS letterheads are usually the company name.
        return line.length >= 3 && line.split(' ').size <= 6 &&
            line.any { it.isLetter() } && line.filter { it.isLetter() }.all { it.isUpperCase() }
    }

    private fun looksLikeAddress(line: String): Boolean {
        if (looksLikePhoneLine(line)) return false
        val lower = line.lowercase(Locale.ROOT)
        val keyword = ADDRESS_WORDS.any { word ->
            Regex("(?i)(^|\\W)${Regex.escape(word)}(\\W|$)").containsMatchIn(line)
        }
        val hasPincode = Regex("\\b\\d{5,6}\\b").containsMatchIn(line)
        return keyword || (hasPincode && line.any { it.isLetter() }) ||
            (line.any(Char::isDigit) && line.split(' ').size >= 3 && lower.contains("no"))
    }

    private fun looksLikeName(line: String): Boolean {
        if (line.any(Char::isDigit) || '@' in line || "www." in line.lowercase(Locale.ROOT)) return false
        if (looksLikeTitle(line) || looksLikeCompany(line) || looksLikeAddress(line)) return false
        val words = line.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false
        if (!line.any { it.isLetter() }) return false
        // A name is text, not a data line: allow any script (Devanagari marks are not
        // isLetter) but reject punctuation used by e-mails, URLs and numbers.
        return line.none { it in "()[]{}<>|\\/+=*#$%^~_`@" }
    }
}
