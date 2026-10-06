package ca.derickcampbell.qrcards.data

import android.content.Context
import ca.derickcampbell.qrcards.model.CardFolder
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.FieldOption
import ca.derickcampbell.qrcards.model.OrderEntry
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
    fun replaceAll(
        cards: List<QrCard>,
        folders: List<CardFolder> = emptyList(),
        order: List<OrderEntry> = emptyList(),
    ) = writeLibrary(cards, folders, order)

    // -- folders --

    @Synchronized
    fun listFolders(): List<CardFolder> = readFolders()

    /** Inserts or replaces by id. Generates an id when blank. */
    @Synchronized
    fun saveFolder(folder: CardFolder): CardFolder {
        val withId =
            if (folder.id.isBlank()) folder.copy(id = UUID.randomUUID().toString()) else folder
        val folders = readFolders().filter { it.id != withId.id } + withId
        // A brand-new folder isn't in the visual order yet; buildRows
        // appends it at the end, same as before.
        writeLibrary(readAll(), folders, readOrder())
        return withId
    }

    /**
     * Deletes a folder. Its cards are never deleted — they move back to the
     * top level, keeping their relative order, and take the folder's slot
     * in the visual order so they land where the folder was.
     */
    @Synchronized
    fun deleteFolder(id: String) {
        val cards = readAll()
        val newCards = cards.map { if (it.folderId == id) it.copy(folderId = null) else it }
        val unfiled = cards.filter { it.folderId == id }
        val newOrder = readOrder().flatMap { entry ->
            if (entry is OrderEntry.Folder && entry.id == id) {
                unfiled.map { OrderEntry.Card(it.id) }
            } else {
                listOf(entry)
            }
        }
        writeLibrary(newCards, readFolders().filter { it.id != id }, newOrder)
    }

    @Synchronized
    fun setFolderCollapsed(id: String, collapsed: Boolean) {
        writeLibrary(
            readAll(),
            readFolders().map { if (it.id == id) it.copy(collapsed = collapsed) else it },
            readOrder(),
        )
    }

    /**
     * Persists a manual reorder of the library list (drag-and-drop).
     * [order] is the visual sequence of folder headers and top-level
     * cards — the thing that lets a top-level card sit above, below, or
     * between folders; [cardIds] the visible card order (children included,
     * in row order); [folderOf] maps card id → containing folder id
     * (null = top level) for the cards that were visible. Cards that
     * weren't visible (children of collapsed folders) keep their folder
     * and relative order at the end, so a partial id list can never lose
     * or unfile a card.
     */
    @Synchronized
    fun saveStructure(
        order: List<OrderEntry>,
        cardIds: List<String>,
        folderOf: Map<String, String?>,
    ) {
        val folders = readFolders()
        val fById = folders.associateBy { it.id }
        val orderedFolderIds = order.filterIsInstance<OrderEntry.Folder>()
            .map { it.id }.toSet()
        val newFolders =
            order.filterIsInstance<OrderEntry.Folder>().mapNotNull { fById[it.id] } +
                folders.filter { it.id !in orderedFolderIds }
        val cards = readAll()
        val cById = cards.associateBy { it.id }
        val wantedCards = cardIds.toSet()
        val newCards = cardIds.mapNotNull { id ->
            val card = cById[id] ?: return@mapNotNull null
            if (folderOf.containsKey(id)) card.copy(folderId = folderOf[id]) else card
        } + cards.filter { it.id !in wantedCards }
        // Drop order entries pointing at unknown ids (hand-edited file);
        // buildRows appends anything missing, so this can't lose content.
        val saneOrder = order.filter {
            when (it) {
                is OrderEntry.Folder -> it.id in fById
                is OrderEntry.Card -> it.id in cById
            }
        }
        writeLibrary(newCards, newFolders, saneOrder)
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

    private fun readFolders(): List<CardFolder> {
        val f = file
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("folders") ?: JSONArray()
            List(arr.length()) { i -> folderFromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(cards: List<QrCard>) =
        writeLibrary(cards, readFolders(), readOrder())

    private fun writeLibrary(
        cards: List<QrCard>,
        folders: List<CardFolder>,
        order: List<OrderEntry>,
    ) {
        val cardsArr = JSONArray()
        cards.forEach { cardsArr.put(toJson(it)) }
        val foldersArr = JSONArray()
        folders.forEach { foldersArr.put(folderToJson(it)) }
        val orderArr = JSONArray()
        order.forEach {
            orderArr.put(
                when (it) {
                    is OrderEntry.Folder -> JSONObject().put("t", "f").put("id", it.id)
                    is OrderEntry.Card -> JSONObject().put("t", "c").put("id", it.id)
                }
            )
        }
        file.writeText(
            JSONObject().put("cards", cardsArr).put("folders", foldersArr)
                .put("order", orderArr).toString()
        )
    }

    /**
     * The persisted visual order of folder headers and top-level cards.
     * Empty for libraries written before interleaved top-level cards
     * existed — callers fall back to folders-first, then top-level cards.
     */
    @Synchronized
    fun readOrder(): List<OrderEntry> {
        val f = file
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("order") ?: JSONArray()
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i) ?: return@List null
                when (o.optString("t")) {
                    "f" -> OrderEntry.Folder(o.getString("id"))
                    "c" -> OrderEntry.Card(o.getString("id"))
                    else -> null
                }
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
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
            .put("folderId", card.folderId)

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
            // Absent in backups written before folders existed.
            folderId = o.optString("folderId").ifEmpty { null },
        )
    }

    internal fun folderToJson(folder: CardFolder): JSONObject =
        JSONObject()
            .put("id", folder.id)
            .put("name", folder.name)
            .put("icon", folder.icon)
            .put("collapsed", folder.collapsed)

    internal fun folderFromJson(o: JSONObject): CardFolder =
        CardFolder(
            id = o.getString("id"),
            name = o.getString("name"),
            icon = o.optString("icon").ifEmpty { "folder" },
            collapsed = o.optBoolean("collapsed", false),
        )
}
