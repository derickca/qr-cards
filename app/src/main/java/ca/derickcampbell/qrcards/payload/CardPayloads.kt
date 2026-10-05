package ca.derickcampbell.qrcards.payload

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builders for the exact strings encoded into QR codes.
 *
 * QR codes carry plain text; the *meaning* comes from de-facto standard
 * payload formats (see the ZXing "Barcode Contents" wiki). Phone cameras
 * parse these formats and offer an action (join network, save contact,
 * open maps, ...). Everything here is static and fully offline.
 *
 * Correctness notes that matter in practice:
 * - In WIFI: (and MECARD) payloads, the characters `\ ; , : "` inside values
 *   MUST be backslash-escaped. An unescaped `;` in a password silently
 *   truncates the payload — the #1 reason Wi-Fi codes "scan but never connect".
 * - Never trim leading/trailing spaces in SSIDs or passwords: they are legal.
 * - vCard 3.0 has the widest real-world support (Apple/Google Contacts,
 *   Outlook). vCard 4.0 extras are not reliably parsed, so we emit 3.0.
 * - Keep contact payloads lean (name/phone/email/org/URL). Photos and long
 *   addresses bloat the code and make it harder to scan.
 * - QR payloads are PLAINTEXT readable by any camera. Never put secrets here.
 */
object CardPayloads {

    /** URL. Adds https:// when no scheme is present; scanners need the scheme. */
    fun url(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "URL must not be empty" }
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
    }

    /**
     * Wi-Fi join code. `T:WPA` lets the phone and router negotiate the actual
     * mode (covers WPA2/WPA3); both iOS 11+ and Android 10+ cameras handle it
     * natively and always prompt before joining — no silent join.
     */
    fun wifi(ssid: String, password: String, hidden: Boolean = false): String {
        require(ssid.isNotEmpty()) { "SSID must not be empty" }
        val hiddenPart = if (hidden) ";H:true" else ""
        return "WIFI:T:WPA;S:${wifiEscape(ssid)};P:${wifiEscape(password)}$hiddenPart;;"
    }

    private fun wifiEscape(value: String): String =
        value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")

    /**
     * Contact card as vCard 3.0. Opens the system's "new contact" form
     * pre-filled; the user confirms the save.
     */
    fun contact(
        firstName: String,
        lastName: String = "",
        organization: String = "",
        phone: String = "",
        email: String = "",
        website: String = "",
    ): String {
        require(firstName.isNotBlank() || lastName.isNotBlank()) { "Contact needs a name" }
        val fullName = listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotEmpty() }.joinToString(" ")
        val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        lines += "N:${vcardEscape(lastName)};${vcardEscape(firstName)};;;"
        lines += "FN:${vcardEscape(fullName)}"
        if (organization.isNotBlank()) lines += "ORG:${vcardEscape(organization.trim())}"
        if (phone.isNotBlank()) lines += "TEL;TYPE=CELL:${phone.trim()}"
        if (email.isNotBlank()) lines += "EMAIL;TYPE=HOME:${email.trim()}"
        if (website.isNotBlank()) lines += "URL:${url(website)}"
        lines += "END:VCARD"
        return lines.joinToString("\n")
    }

    private fun vcardEscape(value: String): String =
        value.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")

    /** Geographic location (RFC 5870). Opens in the default maps app. */
    fun location(latitude: Double, longitude: Double): String {
        require(latitude in -90.0..90.0) { "Latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude out of range" }
        return "geo:$latitude,$longitude"
    }

    /**
     * Shareable Google Maps link for a location. Unlike the geo: payload
     * (which chat apps don't linkify), an https maps URL is tappable
     * everywhere and opens directly in Google Maps.
     */
    fun mapsUrl(latitude: String, longitude: String): String =
        "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"

    /** Plain text. The scanner shows it and offers copy. */
    fun text(content: String): String {
        require(content.isNotEmpty()) { "Text must not be empty" }
        return content
    }

    /** Email. Opens compose with recipient/subject/body pre-filled. */
    fun email(address: String, subject: String = "", body: String = ""): String {
        require(address.isNotBlank()) { "Email address must not be empty" }
        val params = buildString {
            if (subject.isNotBlank()) append("subject=${urlEncode(subject)}")
            if (body.isNotBlank()) {
                if (isNotEmpty()) append("&")
                append("body=${urlEncode(body)}")
            }
        }
        return "mailto:${address.trim()}" + if (params.isNotEmpty()) "?$params" else ""
    }

    /** Phone number. Opens the dialer pre-filled — never auto-dials. */
    fun phone(number: String): String {
        require(number.isNotBlank()) { "Phone number must not be empty" }
        return "tel:${number.trim()}"
    }

    /** SMS. Opens the messaging app with recipient and text pre-filled. */
    fun sms(number: String, message: String = ""): String {
        require(number.isNotBlank()) { "Phone number must not be empty" }
        return "SMSTO:${number.trim()}:${message.trim()}"
    }

    /**
     * Calendar event (iCalendar VEVENT). Offers "add to calendar".
     * Times are local floating times (no timezone suffix).
     */
    fun calendarEvent(
        summary: String,
        start: LocalDateTime,
        end: LocalDateTime,
        location: String = "",
    ): String {
        require(summary.isNotBlank()) { "Event needs a title" }
        require(!end.isBefore(start)) { "Event end must not be before start" }
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val lines = mutableListOf(
            "BEGIN:VEVENT",
            "SUMMARY:${vcardEscape(summary.trim())}",
            "DTSTART:${start.format(fmt)}",
            "DTEND:${end.format(fmt)}",
        )
        if (location.isNotBlank()) lines += "LOCATION:${vcardEscape(location.trim())}"
        lines += "END:VEVENT"
        return lines.joinToString("\n")
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
