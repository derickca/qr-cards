package ca.derickcampbell.qrcards.model

/**
 * One named QR card in the user's library. The payload is the exact string
 * encoded into the QR code (a de-facto standard format such as a URL, a
 * vCard 3.0 block, or a WIFI: join string — see SPEC-v1.md technical notes).
 *
 * Payloads are plaintext readable by any camera: never store secrets here.
 */
data class QrCard(
    val id: String,
    /** User-visible name, e.g. "Work contact", "Guest Wi-Fi". */
    val name: String,
    val type: CardType,
    /** Exact string encoded into the QR code. */
    val payload: String,
    /**
     * The raw form inputs the payload was built from (e.g. ssid/password for
     * Wi-Fi). Lets the editor pre-fill on edit and keeps structured data for
     * future sharing — the payload itself stays the source of truth for
     * rendering.
     */
    val fields: Map<String, String> = emptyMap(),
    val labelColor: Int? = null,
    /** When true, the app confirms before displaying the code. */
    val sensitive: Boolean = false,
)

enum class CardType {
    URL,
    CONTACT,
    WIFI,
    LOCATION,
    TEXT,
    EMAIL,
    PHONE,
    SMS,
    CALENDAR_EVENT,
}
