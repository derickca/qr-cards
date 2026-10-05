package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.res.ColorStateList
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
     * Moves a row; returns true when the positions were valid. Dragging a
     * folder header moves the folder with its visible children as one
     * block. Dropping a card onto a folder header files it inside as the
     * first child — except at the very top of the list, where dropping
     * above the first folder unfiles the card to the top level. The host
     * derives folder assignments from the row order on drop.
     */
    fun move(from: Int, to: Int): Boolean {
        if (from !in rows.indices || to !in rows.indices || from == to) return false
        val mutable = rows.toMutableList()
        val row = mutable[from]
        if (row is LibraryRow.FolderRow) {
            var end = from
            while (end + 1 < mutable.size &&
                mutable[end + 1] is LibraryRow.CardRow &&
                (mutable[end + 1] as LibraryRow.CardRow).inFolder
            ) {
                end++
            }
            val block = mutable.subList(from, end + 1).toList()
            repeat(block.size) { mutable.removeAt(from) }
            val insertAt = (if (to > from) to - block.size + 1 else to)
                .coerceIn(0, mutable.size)
            mutable.addAll(insertAt, block)
            rows = mutable
            notifyDataSetChanged()
        } else {
            val card = mutable.removeAt(from)
            var insertAt = to.coerceIn(0, mutable.size)
            // Onto a folder header → first child of that folder; above the
            // first header → top level (the unfile escape hatch).
            if (insertAt > 0 && mutable.getOrNull(insertAt) is LibraryRow.FolderRow) {
                insertAt++
            }
            mutable.add(insertAt, card)
            rows = mutable
            notifyItemMoved(from, insertAt)
        }
        return true
    }

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
            binding.folderName.setOnClickListener { onFolderRename(folder) }
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
            val startPad = ((if (inFolder) 40 else 16) * density).toInt()
            binding.root.setPadding(startPad, vPad, (16 * density).toInt(), vPad)
            binding.cardName.text = card.name
            binding.cardType.text = typeLabel(card.type, context)
            val color = card.labelColor
            if (color == null) {
                // INVISIBLE (not GONE) keeps the text column aligned.
                binding.colorDot.visibility = View.INVISIBLE
            } else {
                binding.colorDot.visibility = View.VISIBLE
                binding.colorDot.imageTintList = ColorStateList.valueOf(color)
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
    }
}
