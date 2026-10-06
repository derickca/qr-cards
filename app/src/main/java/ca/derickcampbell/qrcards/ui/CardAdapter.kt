package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ca.derickcampbell.qrcards.R
import ca.derickcampbell.qrcards.databinding.ItemCardBinding
import ca.derickcampbell.qrcards.databinding.ItemFolderBinding
import ca.derickcampbell.qrcards.model.CardFolder
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.QrCard

/** Human-readable label for a card type. */
fun typeLabel(type: CardType, context: Context): String = when (type) {
    CardType.URL -> context.getString(R.string.type_url)
    CardType.CONTACT -> context.getString(R.string.type_contact)
    CardType.WIFI -> context.getString(R.string.type_wifi)
    CardType.LOCATION -> context.getString(R.string.type_location)
    CardType.TEXT -> context.getString(R.string.type_text)
    CardType.EMAIL -> context.getString(R.string.type_email)
    CardType.PHONE -> context.getString(R.string.type_phone)
    CardType.SMS -> context.getString(R.string.type_sms)
    CardType.CALENDAR_EVENT -> context.getString(R.string.type_calendar_event)
}

/** One visible row in the library list: a folder header or a card. */
sealed interface LibraryRow {
    data class FolderRow(val folder: CardFolder) : LibraryRow
    data class CardRow(val card: QrCard, val inFolder: Boolean) : LibraryRow
}

/**
 * Where a dragged row lands when released. [IntoFolder] files a card as
 * the first child of the folder; [AtGap] inserts before [insertPos] —
 * [folderId] null means a top-level landing (above, below, or between
 * folders), non-null means as a child of that folder at that position.
 * The host draws the matching indicator while dragging so the landing
 * spot is never a surprise.
 */
sealed interface DropTarget {
    data object None : DropTarget
    data class IntoFolder(val folderId: String, val headerPos: Int) : DropTarget
    data class AtGap(val insertPos: Int, val folderId: String?) : DropTarget
}

/** Maps a folder icon key to its drawable; unknown keys fall back to folder. */
fun folderIconRes(icon: String): Int = when (icon) {
    "star" -> R.drawable.ic_folder_star
    "home" -> R.drawable.ic_folder_home
    "work" -> R.drawable.ic_folder_work
    else -> R.drawable.ic_folder
}

/**
 * Library rows: folder headers (icon, name, expand chevron, drag handle)
 * and cards (name, type label, color dot, drag handle). Children of a
 * folder are indented so the grouping reads at a glance.
 */
class CardAdapter(
    private val onCardClick: (QrCard) -> Unit,
    private val onFolderToggle: (CardFolder) -> Unit = {},
    private val onFolderRename: (CardFolder) -> Unit = {},
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<LibraryRow> = emptyList()

    /**
     * Drag handles show only when the full (unfiltered) library is visible —
     * reordering a search result would be meaningless.
     */
    var dragEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /**
     * Compact rows (tighter vertical padding) once the list gets long.
     * Set by the host based on the visible row count.
     */
    var compact: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(rows: List<LibraryRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    fun currentRows(): List<LibraryRow> = rows

    /**
     * Applies a finished drag: removes the row at [fromPos] and inserts it
     * at [target], taking folder membership from the target — never from
     * whatever header happens to precede the landing spot. Dragging a
     * folder header moves the folder with its visible children as one
     * block; folders can't nest, so a folder dropped onto a header lands
     * before it instead. Returns false when the drop changes nothing.
     */
    fun applyDrop(fromPos: Int, target: DropTarget): Boolean {
        if (fromPos !in rows.indices || target is DropTarget.None) return false
        val mutable = rows.toMutableList()
        return when (val dragged = mutable[fromPos]) {
            is LibraryRow.FolderRow -> {
                var end = fromPos
                while (end + 1 < mutable.size &&
                    mutable[end + 1] is LibraryRow.CardRow &&
                    (mutable[end + 1] as LibraryRow.CardRow).inFolder
                ) {
                    end++
                }
                val block = mutable.subList(fromPos, end + 1).toList()
                repeat(block.size) { mutable.removeAt(fromPos) }
                val rawInsert = when (target) {
                    is DropTarget.IntoFolder -> target.headerPos
                    is DropTarget.AtGap -> target.insertPos
                    is DropTarget.None -> return false
                }
                // rawInsert is in pre-removal coordinates: dropping the
                // removed block shifts everything after fromPos down.
                var insertAt = rawInsert
                if (rawInsert > fromPos) insertAt -= block.size
                insertAt = insertAt.coerceIn(0, mutable.size)
                if (insertAt == fromPos) return false
                mutable.addAll(insertAt, block)
                rows = mutable
                notifyDataSetChanged()
                true
            }
            is LibraryRow.CardRow -> {
                mutable.removeAt(fromPos)
                val (rawInsert, newFolderId) = when (target) {
                    is DropTarget.IntoFolder -> (target.headerPos + 1) to target.folderId
                    is DropTarget.AtGap -> target.insertPos to target.folderId
                    is DropTarget.None -> return false
                }
                // rawInsert is in pre-removal coordinates: dropping the
                // removed row shifts everything after fromPos down by one.
                var insertAt = rawInsert
                if (rawInsert > fromPos) insertAt--
                insertAt = insertAt.coerceIn(0, mutable.size)
                if (insertAt == fromPos && dragged.card.folderId == newFolderId) return false
                val card = if (dragged.card.folderId == newFolderId) dragged.card
                else dragged.card.copy(folderId = newFolderId)
                mutable.add(
                    insertAt,
                    LibraryRow.CardRow(card, inFolder = newFolderId != null)
                )
                rows = mutable
                notifyDataSetChanged()
                true
            }
        }
    }

    /** True when the row at [position] is a folder header. */
    fun isFolderRow(position: Int): Boolean =
        position in rows.indices && rows[position] is LibraryRow.FolderRow

    /** True when the row at [position] is a card filed inside a folder. */
    fun isChildRow(position: Int): Boolean =
        (rows.getOrNull(position) as? LibraryRow.CardRow)?.inFolder == true

    /** Folder id of the card row at [position], or null for top-level cards. */
    fun cardFolderId(position: Int): String? =
        (rows.getOrNull(position) as? LibraryRow.CardRow)?.card?.folderId

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is LibraryRow.FolderRow -> VIEW_FOLDER
        is LibraryRow.CardRow -> VIEW_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_FOLDER) {
            FolderHolder(ItemFolderBinding.inflate(inflater, parent, false))
        } else {
            CardHolder(ItemCardBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibraryRow.FolderRow -> (holder as FolderHolder).bind(row.folder)
            is LibraryRow.CardRow -> (holder as CardHolder).bind(row.card, row.inFolder)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun verticalPad(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return ((if (compact) 10 else 16) * density).toInt()
    }

    inner class FolderHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: CardFolder) {
            val context = binding.root.context
            val vPad = verticalPad(context)
            binding.root.setPadding(
                binding.root.paddingLeft, vPad, binding.root.paddingRight, vPad
            )
            binding.folderIcon.setImageResource(folderIconRes(folder.icon))
            binding.folderName.text = folder.name
            binding.expandIcon.setImageResource(
                if (folder.collapsed) R.drawable.ic_chevron_down
                else R.drawable.ic_chevron_up
            )
            binding.expandIcon.contentDescription = context.getString(
                if (folder.collapsed) R.string.expand_folder else R.string.collapse_folder
            )
            binding.expandIcon.setOnClickListener { onFolderToggle(folder) }
            binding.root.setOnClickListener { onFolderToggle(folder) }
            // Tap zones on the name row: the words themselves rename; the
            // blank space after the words toggles. folderName fills the row
            // width (weight=1), so the tap's x position decides the zone.
            // The touch listener only records; returning false keeps the
            // ripple and click handling intact.
            var lastTapX = 0f
            binding.folderName.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTapX = event.x
                false
            }
            binding.folderName.setOnClickListener {
                val tv = binding.folderName
                // Laid-out width (not the full text): ellipsized names keep
                // their full blank zone.
                val textWidth = tv.layout?.getLineWidth(0)
                    ?: tv.paint.measureText(tv.text.toString())
                val wordsEnd = tv.paddingStart + textWidth
                if (lastTapX <= wordsEnd) onFolderRename(folder)
                else onFolderToggle(folder)
            }
            binding.dragHandle.visibility =
                if (dragEnabled) View.VISIBLE else View.GONE
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                    onStartDrag(this)
                }
                false
            }
        }
    }

    inner class CardHolder(private val binding: ItemCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: QrCard, inFolder: Boolean) {
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            val vPad = verticalPad(context)
            // Alignment: a card's name starts where a folder's name starts
            // (row padding 16 + folder icon 24 + its margin 16 = 56dp), and
            // an in-folder card's icon starts there too.
            val startPad = (
                if (inFolder) FOLDER_NAME_START_DP
                else FOLDER_NAME_START_DP - CARD_DOT_DP - CARD_DOT_MARGIN_DP
                ) * density
            binding.root.setPadding(
                startPad.toInt(), vPad, (16 * density).toInt(), vPad
            )
            binding.cardName.text = card.name
            binding.cardType.text = typeLabel(card.type, context)
            val color = card.labelColor
            if (color == null) {
                // INVISIBLE (not GONE) keeps the text column aligned.
                binding.colorDot.visibility = View.INVISIBLE
                binding.colorDot.background = null
            } else {
                binding.colorDot.visibility = View.VISIBLE
                binding.colorDot.imageTintList = ColorStateList.valueOf(color)
                // Dark dots vanish on dark rows: echo the editor's
                // selection ring so black (and other dark colors) stay
                // visible in the list.
                binding.colorDot.background =
                    if (isDark(color)) context.getDrawable(R.drawable.dot_ring)
                    else null
            }
            binding.root.setOnClickListener { onCardClick(card) }
            binding.dragHandle.visibility =
                if (dragEnabled) View.VISIBLE else View.GONE
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                    onStartDrag(this)
                }
                false
            }
        }
    }

    companion object {
        private const val VIEW_FOLDER = 0
        private const val VIEW_CARD = 1
        /**
         * Row geometry, dp: folder icon (24) + its trailing margin (16) +
         * row padding (16) = 56, the left edge of every folder name.
         */
        private const val FOLDER_NAME_START_DP = 56
        private const val CARD_DOT_DP = 12
        private const val CARD_DOT_MARGIN_DP = 16

        /** True for colors too dark to see on a dark row (black, navy...). */
        private fun isDark(color: Int): Boolean {
            val lum = 0.2126f * Color.red(color) / 255f +
                0.7152f * Color.green(color) / 255f +
                0.0722f * Color.blue(color) / 255f
            return lum < 0.25f
        }
    }
}
