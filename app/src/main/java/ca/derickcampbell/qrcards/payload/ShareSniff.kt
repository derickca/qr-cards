package ca.derickcampbell.qrcards.payload

import android.util.Patterns
import ca.derickcampbell.qrcards.model.CardType

/**
 * Guesses a card type + prefill fields for text shared into the app from
 * Android's share sheet. The guess only ever pre-selects the type tab and
 * fills the form — the user lands in the editor and confirms before
 * anything is saved, so a wrong guess costs one tap, never a bad card.
 *
 * Priority (Derick's call): vCard → Wi-Fi → Google Maps link → URL →
 * phone/email (as a Contact card — a shared number or address is almost
 * always "add this person"; the narrow Phone/Email cards are one tab tap
 * away) → lat,lng → Text.
 */
object ShareSniff {

    /** A type guess plus the editor field map to prefill. */
    data class Draft(
        val type: CardType,
        val fields: Map<String, String> = emptyMap(),
    )

    fun sniff(raw: String): Draft {
        val text = raw.trim()
        if (text.isEmpty()) return Draft(CardType.TEXT)

        // vCard block — parse the common fields into a contact draft.
        if (text.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            return Draft(CardType.CONTACT, parseVCard(text))
        }

        // Wi-Fi join string: WIFI:T:WPA;S:ssid;P:password;; (unescaped for edit)
        parseWifi(text)?.let { return Draft(CardType.WIFI, it) }

        // Google Maps share → location card with the pinned coordinates.
        // (Short goo.gl links can't be expanded: the app is offline by
        // design, so only the long forms are picked up.)
        parseMapsUrl(text)?.let { (lat, lng) ->
            return Draft(
                CardType.LOCATION,
                mapOf("latitude" to lat, "longitude" to lng),
            )
        }

        // URL.
        if (Patterns.WEB_URL.matcher(text).matches()) {
            return Draft(CardType.URL, mapOf("url" to text))
        }

        // Email address → contact with the email pre-filled.
        if (Patterns.EMAIL_ADDRESS.matcher(text).matches()) {
            return Draft(CardType.CONTACT, mapOf("email" to text))
        }

        // Phone number → contact with the phone pre-filled.
        if (isPhoneLike(text)) {
            return Draft(CardType.CONTACT, mapOf("phone" to text))
        }

        // "lat, lng" (e.g. pasted from a map app).
        parseLatLng(text)?.let { (lat, lng) ->
            return Draft(
                CardType.LOCATION,
                mapOf("latitude" to lat, "longitude" to lng),
            )
        }

        return Draft(CardType.TEXT, mapOf("content" to raw.trim()))
    }

    private val PHONE_CHARS = Regex("^[+\\d][\\d\\s\\-().]{5,}[\\d)]$")

    private fun isPhoneLike(text: String): Boolean {
        if (!PHONE_CHARS.matches(text)) return false
        // Must be mostly digits: "Call me at 5" isn't a phone number.
        return text.count { it.isDigit() } >= 7
    }

    private val LAT_LNG = Regex(
        "^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$"
    )

    private fun parseLatLng(text: String): Pair<String, String>? {
        val m = LAT_LNG.matchEntire(text) ?: return null
        return validLatLng(m.groupValues[1], m.groupValues[2])
    }

    private fun validLatLng(lat: String, lng: String): Pair<String, String>? {
        val la = lat.toDoubleOrNull() ?: return null
        val ln = lng.toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || ln !in -180.0..180.0) return null
        return lat to lng
    }

    /**
     * Google Maps shared links, long form:
     * - https://www.google.com/maps/@49.28,-123.12,15z
     * - https://www.google.com/maps/place/Foo/@49.28,-123.12,15z
     * - https://www.google.com/maps/search/?api=1&query=49.28,-123.12
     * - https://maps.google.com/?q=49.28,-123.12
     */
    private fun parseMapsUrl(text: String): Pair<String, String>? {
        val lower = text.lowercase()
        val isMaps = (lower.contains("google.") && lower.contains("/maps")) ||
            lower.contains("maps.google.")
        if (!isMaps) return null
        // @lat,lng[,zoom] form.
        Regex("@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)").find(text)?.let {
            return validLatLng(it.groupValues[1], it.groupValues[2])
        }
        // ?q=lat,lng or ?query=lat,lng form.
        Regex("[?&](?:q|query)=(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")
            .find(text)?.let {
                return validLatLng(it.groupValues[1], it.groupValues[2])
            }
        return null
    }

    private val WIFI_PATTERN = Regex(
        "^WIFI:T:([^;]*);S:((?:[^;\\\\]|\\\\.)*);P:((?:[^;\\\\]|\\\\.)*);.*$",
        RegexOption.IGNORE_CASE,
    )

    private fun wifiUnescape(value: String): String =
        value.replace("\\\\", "\\")
            .replace("\\;", ";")
            .replace("\\,", ",")
            .replace("\\:", ":")
            .replace("\\\"", "\"")

    private fun parseWifi(text: String): Map<String, String>? {
        val m = WIFI_PATTERN.matchEntire(text.trim()) ?: return null
        return mapOf(
            "ssid" to wifiUnescape(m.groupValues[2]),
            "password" to wifiUnescape(m.groupValues[3]),
        )
    }

    /**
     * Best-effort vCard → contact fields. Only the fields our editor has;
     * the full block still ends up in the payload on save via the builder.
     */
    private fun parseVCard(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        // Unfold continuation lines, then split into properties.
        val unfolded = text.replace(Regex("\r?\n[ \t]"), "")
        for (line in unfolded.split(Regex("\r?\n"))) {
            val prop = line.substringBefore(":")
            val value = line.substringAfter(":", "")
            val key = prop.substringBefore(";").uppercase()
            when (key) {
                "FN" -> {
                    val parts = value.trim().split("\\s+".toRegex())
                    if (out["firstName"].isNullOrEmpty()) {
                        out["firstName"] = parts.dropLast(1).joinToString(" ")
                            .ifEmpty { parts.firstOrNull().orEmpty() }
                        out["lastName"] = parts.lastOrNull().orEmpty()
                            .takeIf { parts.size > 1 }.orEmpty()
                    }
                    // The card name for a shared contact: lifted out by the
                    // editor into the name field (not an editor field).
                    if (out["name"].isNullOrEmpty()) out["name"] = value.trim()
                }
                "N" -> {
                    // N:Last;First;;; — prefer over the FN guess when present.
                    val comps = value.split(";")
                    if (comps.size >= 2) {
                        out["lastName"] = comps[0].trim()
                        out["firstName"] = comps[1].trim()
                    }
                }
                "TEL" -> if (out["phone"].isNullOrEmpty()) out["phone"] = value.trim()
                "EMAIL" -> if (out["email"].isNullOrEmpty()) out["email"] = value.trim()
                "ORG" -> if (out["organization"].isNullOrEmpty()) {
                    out["organization"] = value.split(";").firstOrNull()?.trim().orEmpty()
                }
                "URL" -> if (out["website"].isNullOrEmpty()) out["website"] = value.trim()
            }
        }
        return out.mapValues { (_, v) -> vcardUnescape(v) }
    }

    private fun vcardUnescape(value: String): String =
        value.replace("\\\\", "\\")
            .replace("\\;", ";")
            .replace("\\,", ",")
            .replace("\\n", "\n")
            .replace("\\N", "\n")
}
