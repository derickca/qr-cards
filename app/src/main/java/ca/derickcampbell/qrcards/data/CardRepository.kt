package ca.derickcampbell.qrcards.data

import android.content.Context
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.FieldOption
import ca.derickcampbell.qrcards.model.QrCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Card storage: a single JSON file in the app's internal storage.
 *
 * Deliberate choice over Room/SQLite: a dozen cards in a flat file needs no
 * database dependency, keeps the APK tiny, and the whole library is trivially
 * inspectable. If the library ever outgrows this, migrate then — not now.
 *
 * All methods are synchronized; call off the main thread for large libraries
 * (in practice: tens of cards, instant).
 */
class CardRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, "cards.json")

    @Synchronized
    fun list(): List<QrCard> = readAll()

    @Synchronized
    fun get(id: String): QrCard? = readAll().find { it.id == id }

    /** Inserts or replaces by id. Generates an id when blank. */
    @Synchronized
    fun save(card: QrCard): QrCard {
        val withId = if (card.id.isBlank()) card.copy(id = UUID.randomUUID().toString()) else card
        val cards = readAll().filter { it.id != withId.id } + withId
        writeAll(cards)
        return withId
    }

    @Synchronized
    fun delete(id: String) {
        writeAll(readAll().filter { it.id != id })
    }

    /** Replaces the whole library (used by backup restore). */
    @Synchronized
    fun replaceAll(cards: List<QrCard>) = writeAll(cards)

    /**
     * Persists a manual reorder (drag-and-drop in the library). Cards not in
     * [ids] keep their relative order at the end, so a partial id list can
     * never lose a card. Order survives backup/restore: it is the cards
     * array order in the JSON file.
     */
    @Synchronized
    fun saveOrder(ids: List<String>) {
        val all = readAll()
        val byId = all.associateBy { it.id }
        val wanted = ids.toSet()
        writeAll(ids.mapNotNull { byId[it] } + all.filter { it.id !in wanted })
    }

    // -- persistence --

    private fun readAll(): List<QrCard> {
        val f = file
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("cards") ?: JSONArray()
            List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(cards: List<QrCard>) {
        val arr = JSONArray()
        cards.forEach { arr.put(toJson(it)) }
        file.writeText(JSONObject().put("cards", arr).toString())
    }

    internal fun toJson(card: QrCard): JSONObject =
        JSONObject()
            .put("id", card.id)
            .put("name", card.name)
            .put("type", card.type.name)
            .put("payload", card.payload)
            .put("fields", JSONObject(card.fields))
            .put("fieldOptions", JSONObject().apply {
                card.fieldOptions.forEach { (key, options) ->
                    put(key, JSONArray(options.map {
                        JSONObject()
                            .put("label", it.label)
                            .put("value", it.value)
                    }))
                }
            })
            .put("labelColor", card.labelColor)
            .put("sensitive", card.sensitive)
            .put("qrColor", card.qrColor)

    internal fun fromJson(o: JSONObject): QrCard {
        val fieldsObj = o.optJSONObject("fields") ?: JSONObject()
        val fields = mutableMapOf<String, String>()
        fieldsObj.keys().forEach { k -> fields[k] = fieldsObj.optString(k) }
        val fieldOptions = mutableMapOf<String, List<FieldOption>>()
        val optionsObj = o.optJSONObject("fieldOptions")
        optionsObj?.keys()?.forEach { k ->
            val arr = optionsObj.optJSONArray(k) ?: JSONArray()
            fieldOptions[k] = List(arr.length()) { i ->
                val item = arr.optJSONObject(i) ?: JSONObject()
                FieldOption(
                    label = item.optString("label"),
                    value = item.optString("value"),
                )
            }
        }
        return QrCard(
            id = o.getString("id"),
            name = o.getString("name"),
            type = CardType.valueOf(o.getString("type")),
            payload = o.getString("payload"),
            fields = fields,
            fieldOptions = fieldOptions,
            labelColor = if (o.isNull("labelColor")) null else o.getInt("labelColor"),
            sensitive = o.optBoolean("sensitive", false),
            // Absent in backups written before custom QR colors existed.
            qrColor = if (o.isNull("qrColor")) null else o.getInt("qrColor"),
        )
    }
}
