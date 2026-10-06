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
    /**
     * Every known value for a field, not just the selected one. Used by the
     * contact editor: a contact often has several phone numbers or emails, so
     * all of them are kept (with labels) and the user can switch the active
     * one any time. The QR payload only ever carries the selected value from
     * [fields]. Empty for cards created before this existed.
     */
    val fieldOptions: Map<String, List<FieldOption>> = emptyMap(),
    val labelColor: Int? = null,
    /** When true, the app confirms before displaying the code. */
    val sensitive: Boolean = false,
    /**
     * Custom QR module color (ARGB). Null means the default black.
     * Palette is dark-only so codes stay scannable.
     */
    val qrColor: Int? = null,
    /**
     * Id of the containing [CardFolder], or null for top-level cards.
     * Absent in backups written before folders existed.
     */
    val folderId: String? = null,
)

/**
 * A named, collapsible group in the card library. Folders only organize —
 * they carry no QR payload of their own. Deleting a folder never deletes
 * its cards; they move back to the top level.
 */
data class CardFolder(
    val id: String,
    /** User-visible name, e.g. "Work", "Travel". */
    val name: String,
    /**
     * Icon key, one of "folder", "star", "home", "work". Unknown keys fall
     * back to "folder" so a backup from a newer app never breaks this one.
     */
    val icon: String = "folder",
    /** When true the folder's cards are hidden in the library list. */
    val collapsed: Boolean = false,
)

/** One labelled value among several options for a field. */
data class FieldOption(
    val label: String,
    val value: String,
) : java.io.Serializable

/**
 * One slot in the library's visual order: a folder header or a top-level
 * card. Children of a folder are not listed — they render under their
 * folder header in card-array order. This is what lets top-level cards sit
 * above, below, or between folders instead of always trailing after them.
 */
sealed interface OrderEntry {
    data class Folder(val id: String) : OrderEntry
    data class Card(val id: String) : OrderEntry
}

enum class CardType {
    CONTACT,
    URL,
    WIFI,
    LOCATION,
    TEXT,
    EMAIL,
    PHONE,
    SMS,
    CALENDAR_EVENT,
}
